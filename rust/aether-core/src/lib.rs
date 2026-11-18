//! Project AetherIO Omega v7.1 — Production Pure Rust Data Plane (`libaether_core.so`)
//!
//! Compiles on 100% stable Rust (`edition = "2024"`, `panic = "unwind"`).
//! - Thread-safe `BoundedSlabPool` (16 x 512 KiB = 8.0 MiB) with RAII `SlabLeaseGuard`
//! - Zero-JNI 4,096-byte `memfd_create()` cache-line isolated `TelemetryPage`
//! - Android seccomp-safe `fallocate` + `pwrite` + `fdatasync` (+ `io_uring` ENOSYS probe)
//! - Crash-safe `AETHLDG1` binary ledger with CRC32C checksum
//! - Logical `LinkKey` handle rotation + `android_setsocknetwork` + `socks5h://` (`ATYP=0x03`)
//! - Earliest-Arrival multipath dispatch + pinned `SegmentLease` + 3-state `LinkCircuitBreaker`
//! - `GF(2^8)` dual-LUT RLNC (`W = 16`) + FastCDC / BLAKE3 / SHA-256 verification

pub mod cas;
pub mod jni_bridge;
pub mod ledger;
pub mod rlnc;
pub mod scheduler;
pub mod storage_and_telemetry;
pub mod transport;

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use crate::cas::{blake3_digest_hex, sha256_digest_hex};
    use crate::ledger::{AetherLedger, SegmentStatus};
    use crate::rlnc::{RLNC_WINDOW, gf256_mul_acc};
    use crate::scheduler::{
        BreakerState, LinkCircuitBreaker, LinkDispatchStats, SegmentLease,
        absorb_empty_right_of, adaptive_bdp_segment_bytes, adjust_auto_speed_up,
        effective_worker_count, select_earliest_arrival_link, select_lowest_rtt_link,
    };
    use crate::storage_and_telemetry::{BoundedSlabPool, MAX_SLABS, SLAB_BYTES, TelemetryPage};
    use std::collections::HashMap;

    #[test]
    fn telemetry_page_is_exact_4096_bytes() {
        assert_eq!(core::mem::size_of::<TelemetryPage>(), 4096);
    }

    #[test]
    fn bounded_slab_pool_returns_buffer_on_drop() {
        let pool = BoundedSlabPool::new();
        assert_eq!(pool.allocated_bytes(), (MAX_SLABS * SLAB_BYTES) as u64);
        {
            let _guard = pool.acquire_guard();
            assert_eq!(pool.in_use_bytes(), SLAB_BYTES as u64);
        }
        assert_eq!(pool.in_use_bytes(), 0);
    }

    #[test]
    fn aethldg1_binary_ledger_roundtrip_and_crash_lease_rollback() {
        let mut ledger = AetherLedger::new(10 * 1024 * 1024, 4 * 1024 * 1024, "etag-v1");
        assert_eq!(ledger.segment_count, 3);
        ledger.states[0] = SegmentStatus::Done as u8;
        ledger.states[1] = SegmentStatus::Leased as u8; // Simulate mid-flight crash
        let bytes = ledger.encode();
        let decoded = AetherLedger::decode(&bytes).expect("valid AETHLDG1 ledger");
        assert_eq!(decoded.states[0], SegmentStatus::Done as u8);
        assert_eq!(decoded.states[1], SegmentStatus::Pending as u8); // Reverted cleanly
        assert_eq!(decoded.states[2], SegmentStatus::Pending as u8);
    }

    #[test]
    fn earliest_arrival_and_circuit_breaker_behave_deterministically() {
        let mut breakers = HashMap::new();
        let now = Instant::now();
        let links = vec![
            LinkDispatchStats {
                link_id: "wifi:wlan0".into(),
                queued_bytes: 0.0,
                ewma_bytes_per_sec: 4_125_000.0, // ~33 Mbps
                rtt_min_secs: 0.018,
            },
            LinkDispatchStats {
                link_id: "cell:rmnet0".into(),
                queued_bytes: 0.0,
                ewma_bytes_per_sec: 20_412_500.0, // ~163.3 Mbps
                rtt_min_secs: 0.028,
            },
        ];

        // Earliest arrival selects 5G for a 4 MiB block
        let picked = select_earliest_arrival_link(&links, &mut breakers, 4_194_304.0, now);
        assert_eq!(picked, Some("cell:rmnet0"));

        // HEAD probe selects lowest RTT (Wi-Fi 18 ms)
        let probe = select_lowest_rtt_link(&links, &mut breakers, now);
        assert_eq!(probe, Some("wifi:wlan0"));

        // Trip 5G breaker -> falls back to Wi-Fi immediately
        breakers
            .get_mut("cell:rmnet0")
            .unwrap()
            .record_error_or_stall(now, true);
        assert_eq!(
            breakers["cell:rmnet0"].state,
            BreakerState::Open
        );
        let fallback = select_earliest_arrival_link(&links, &mut breakers, 4_194_304.0, now);
        assert_eq!(fallback, Some("wifi:wlan0"));

        // After cooldown -> HalfOpen allows probe
        let later = now + Duration::from_secs(11);
        assert!(breakers.get_mut("cell:rmnet0").unwrap().allow_request(later));
        assert_eq!(breakers["cell:rmnet0"].state, BreakerState::HalfOpen);
    }

    #[test]
    fn adaptive_bdp_worker_scaling_and_neighbor_absorption() {
        assert_eq!(adaptive_bdp_segment_bytes(33_000), 1024 * 1024);
        assert_eq!(adaptive_bdp_segment_bytes(163_300), 8 * 1024 * 1024);
        assert_eq!(effective_worker_count(1, 2), 2); // F8 fix

        let mut states = vec![2u8, 1u8, 0u8, 0u8];
        assert_eq!(absorb_empty_right_of(&mut states, 1), Some(2));
        assert_eq!(states[2], 1);

        assert_eq!(adjust_auto_speed_up(4, 2, 8, 50.0, 60.0, 8.0, false), 6);
        assert_eq!(adjust_auto_speed_up(6, 2, 8, 60.0, 60.0, 42.0, false), 4);
        let lease = SegmentLease {
            index: 2,
            start: 0,
            end: 1023,
            pinned_link: "cell:rmnet0".into(),
            is_tail_hedge: true,
        };
        assert_eq!(lease.pinned_link, "cell:rmnet0");
    }

    #[test]
    fn rlnc_and_hashes_work() {
        let mut dst = [0u8; RLNC_WINDOW];
        let src = [0x5Au8; RLNC_WINDOW];
        gf256_mul_acc(&mut dst, &src, 0x07);
        assert!(dst.iter().any(|&b| b != 0));
        assert_eq!(blake3_digest_hex(b"aether").len(), 64);
        assert_eq!(sha256_digest_hex(b"aether").len(), 64);
        let _ = LinkCircuitBreaker::new(Duration::from_secs(10));
    }
}
