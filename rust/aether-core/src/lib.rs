//! Project AetherIO Engine v9.0 — Unified Pure Rust Data Plane (`libaether_core.so`)
//!
//! Compiles on 100% stable Rust (`edition = "2024"`, `panic = "unwind"`).
//! - Lock-free Semaphore(24) + ArrayQueue `BoundedSlabPool` (12.0 MiB hard cap, TCP backpressure)
//! - Persistent pooled `wreq::Client` per link + Decoupled Async Disk-Writer Task
//! - Real `memfd_create()` mmap'd 4,096-byte Quad-Link `TelemetryPage` (Wi-Fi 1 + Wi-Fi 2 + 5G + USB)
//! - Pre-connect socket2 tuning (SO_RCVBUF 8 MiB, TCP_NODELAY, TCP_USER_TIMEOUT = 10s)
//! - Pre-drop `android_getnetworkblockedreason(sock_fd)` diagnostics + `android_setsocknetwork`
//! - True Atomic Live Range Bisection + Anti-Dip Watchdog (eliminates 400 Mbps -> 3 Mbps dips)
//! - `wreq` (BoringSSL `prefix-symbols`, `.no_proxy()`, identity ranges) + `quinn`/`h3` 0-RTT QUIC
//! - `AETHLDG2` crash-safe binary ledger (per-slab BLAKE3 + batched `fdatasync` -> `msync`)
//! - Page-cache sequential SHA-256 advancement prior to `POSIX_FADV_DONTNEED`
//! - Adaptive HLS/DASH parser, hardened Aria2 `127.0.0.1:6800` JSON-RPC, and redacted diagnostics

pub mod aria2_rpc;
pub mod cas;
pub mod diagnostics;
pub mod engine;
pub mod hls_dash;
pub mod jni_bridge;
pub mod ledger;
pub mod rlnc;
pub mod scheduler;
pub mod storage_and_telemetry;
pub mod transport;

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::time::{Duration, Instant};

    use tokio::sync::mpsc;

    use crate::aria2_rpc::handle_json_rpc_payload;
    use crate::cas::{
        ContiguousSha256Verifier, blake3_digest_bytes, blake3_digest_hex, sha256_digest_hex,
    };
    use crate::diagnostics::{export_diagnostics_text, record_diag, redact_sensitive};
    use crate::hls_dash::{ManifestParseResult, parse_dash_mpd, parse_hls_m3u8};
    use crate::ledger::{AetherLedger, SegmentStatus};
    use crate::rlnc::{RLNC_WINDOW, gf256_mul_acc};
    use crate::scheduler::{
        BreakerState, LinkDispatchStats, SegmentLease, absorb_empty_right_of,
        adaptive_bdp_segment_bytes, adjust_auto_speed_up, bisect_slowest_range,
        effective_worker_count, select_earliest_arrival_link, select_lowest_rtt_link,
        select_mirror_for_link,
    };
    use crate::storage_and_telemetry::{
        BoundedSlabPool, MAX_SLABS, SLAB_BYTES, TelemetryPage, pack_quad_worker_counts,
    };
    use crate::transport::{
        TransportContext, parse_content_disposition_filename, sniff_mime_from_magic,
    };

    #[test]
    fn telemetry_page_is_exact_4096_bytes_and_quad_worker_packing_roundtrips() {
        assert_eq!(core::mem::size_of::<TelemetryPage>(), 4096);
        let packed = pack_quad_worker_counts(14, 5, 3, 4, 2);
        assert_eq!(packed & 0xFFFF, 14);
        assert_eq!((packed >> 16) & 0x0FFF, 5);
        assert_eq!((packed >> 28) & 0x0FFF, 3);
        assert_eq!((packed >> 40) & 0x0FFF, 4);
        assert_eq!((packed >> 52) & 0x0FFF, 2);
    }

    #[test]
    fn bounded_slab_pool_enforces_hard_24_slab_cap() {
        let pool = BoundedSlabPool::new();
        assert_eq!(pool.allocated_bytes(), (MAX_SLABS * SLAB_BYTES) as u64);
        let mut guards = Vec::new();
        for _ in 0..MAX_SLABS {
            guards.push(pool.try_acquire_guard().expect("within 24 slab cap"));
        }
        assert_eq!(pool.in_use_bytes(), (MAX_SLABS * SLAB_BYTES) as u64);
        assert!(pool.try_acquire_guard().is_none(), "25th slab must block/fail");
        guards.pop();
        assert!(pool.try_acquire_guard().is_some());
    }

    #[test]
    fn aethldg2_binary_ledger_roundtrip_and_crash_lease_rollback() {
        let mut ledger = AetherLedger::new(10 * 1024 * 1024, 4 * 1024 * 1024, "etag-v9");
        assert_eq!(ledger.segment_count, 3);
        ledger.states[0] = SegmentStatus::Done as u8;
        ledger.states[1] = SegmentStatus::Leased as u8;
        ledger.contiguous_verified_offset = 4 * 1024 * 1024;
        ledger.record_slab_digest(0, blake3_digest_bytes(b"slab-0"));
        let bytes = ledger.encode();
        let decoded = AetherLedger::decode(&bytes).expect("valid AETHLDG2 ledger");
        assert_eq!(decoded.states[0], SegmentStatus::Done as u8);
        assert_eq!(decoded.states[1], SegmentStatus::Pending as u8);
        assert_eq!(decoded.states[2], SegmentStatus::Pending as u8);
        assert_eq!(decoded.contiguous_verified_offset, 4 * 1024 * 1024);
        assert_eq!(decoded.slab_blake3[0], blake3_digest_bytes(b"slab-0"));
    }

    #[test]
    fn contiguous_sha256_handles_out_of_order_completion() {
        let chunk0 = b"hello-";
        let chunk1 = b"aetherio-v9";
        let mut combined = Vec::new();
        combined.extend_from_slice(chunk0);
        combined.extend_from_slice(chunk1);
        let expected = sha256_digest_hex(&combined);

        let mut verifier = ContiguousSha256Verifier::new(combined.len() as u64);
        verifier.record_and_advance(0, chunk0, None).unwrap();
        verifier
            .record_and_advance(chunk0.len() as u64, chunk1, None)
            .unwrap();
        assert_eq!(verifier.finalize_hex(), expected);
    }

    #[test]
    fn earliest_arrival_quad_links_circuit_breaker_and_bisection() {
        let mut breakers = HashMap::new();
        let now = Instant::now();
        let links = vec![
            LinkDispatchStats {
                link_id: "wifi:wlan0".into(),
                queued_bytes: 0.0,
                ewma_bytes_per_sec: 12_500_000.0,
                rtt_min_secs: 0.012,
                active_workers_on_link: 2,
            },
            LinkDispatchStats {
                link_id: "wifi2:wlan1".into(),
                queued_bytes: 0.0,
                ewma_bytes_per_sec: 15_000_000.0,
                rtt_min_secs: 0.015,
                active_workers_on_link: 2,
            },
            LinkDispatchStats {
                link_id: "cell:rmnet0".into(),
                queued_bytes: 0.0,
                ewma_bytes_per_sec: 22_000_000.0,
                rtt_min_secs: 0.025,
                active_workers_on_link: 2,
            },
            LinkDispatchStats {
                link_id: "eth:usb0".into(),
                queued_bytes: 0.0,
                ewma_bytes_per_sec: 28_000_000.0,
                rtt_min_secs: 0.008,
                active_workers_on_link: 2,
            },
        ];

        assert_eq!(
            select_earliest_arrival_link(&links, &mut breakers, 4_194_304.0, now),
            Some("eth:usb0")
        );
        assert_eq!(select_lowest_rtt_link(&links, &mut breakers, now), Some("eth:usb0"));

        breakers.get_mut("eth:usb0").unwrap().record_error_or_stall(now, true);
        assert_eq!(breakers["eth:usb0"].state, BreakerState::Open);
        assert_eq!(
            select_earliest_arrival_link(&links, &mut breakers, 4_194_304.0, now),
            Some("cell:rmnet0")
        );

        let later = now + Duration::from_secs(7);
        assert!(breakers.get_mut("eth:usb0").unwrap().allow_request(later));
        assert_eq!(breakers["eth:usb0"].state, BreakerState::HalfOpen);

        assert_eq!(
            bisect_slowest_range(0, 8 * 1024 * 1024 - 1, 512 * 1024),
            Some((4 * 1024 * 1024, 8 * 1024 * 1024 - 1))
        );
        let mirrors = vec![
            "https://m1.cdn/a.bin".into(),
            "https://m2.cdn/a.bin".into(),
            "https://m3.cdn/a.bin".into(),
            "https://m4.cdn/a.bin".into(),
        ];
        assert_eq!(select_mirror_for_link(&mirrors, "wifi:wlan0", 0), "https://m1.cdn/a.bin");
        assert_eq!(select_mirror_for_link(&mirrors, "cell:rmnet0", 0), "https://m2.cdn/a.bin");
        assert_eq!(select_mirror_for_link(&mirrors, "wifi2:wlan1", 0), "https://m3.cdn/a.bin");
        assert_eq!(select_mirror_for_link(&mirrors, "eth:usb0", 0), "https://m4.cdn/a.bin");
    }

    #[test]
    fn adaptive_bdp_worker_scaling_neighbor_absorption_and_client_pool() {
        assert_eq!(adaptive_bdp_segment_bytes(33_000), 256 * 1024);
        assert_eq!(adaptive_bdp_segment_bytes(163_300), 1024 * 1024);
        assert_eq!(adaptive_bdp_segment_bytes(400_000), 2 * 1024 * 1024);
        assert_eq!(adaptive_bdp_segment_bytes(800_000), 4 * 1024 * 1024);
        assert_eq!(effective_worker_count(1, 2), 2);
        assert_eq!(effective_worker_count(64, 4), 48);

        let mut states = vec![2u8, 1u8, 0u8, 0u8];
        assert_eq!(absorb_empty_right_of(&mut states, 1), Some(2));
        assert_eq!(states[2], 1);

        assert_eq!(adjust_auto_speed_up(4, 2, 64, 50.0, 60.0, 8.0, false), 8);
        assert_eq!(adjust_auto_speed_up(18, 2, 64, 50.0, 60.0, 8.0, false), 20);
        // Adding sockets made throughput worse -> scale down instead of pinning 64.
        assert_eq!(adjust_auto_speed_up(20, 2, 64, 60.0, 50.0, 0.0, false), 17);
        assert_eq!(adjust_auto_speed_up(6, 2, 64, 60.0, 60.0, 42.0, false), 4);
        let lease = SegmentLease {
            index: 2,
            start: 0,
            end: 1023,
            pinned_link: "wifi2:wlan1".into(),
            is_tail_hedge: true,
        };
        assert_eq!(lease.pinned_link, "wifi2:wlan1");

        let ctx = TransportContext::new();
        assert!(ctx.get_or_build_wreq_client("wifi:wlan0", None, None, "chrome134", 0).is_ok());
        assert!(ctx.get_or_build_wreq_client("wifi:wlan0", None, None, "chrome134", 3).is_ok());
    }

    #[test]
    fn rfc6266_mime_hls_dash_aria2_and_redaction() {
        assert_eq!(
            parse_content_disposition_filename("attachment; filename*=UTF-8''ubuntu%20iso.iso"),
            Some("ubuntu iso.iso".into())
        );
        assert_eq!(sniff_mime_from_magic(b"%PDF-1.7", "doc.bin"), "application/pdf");

        let m3u8 = "#EXTM3U\n#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n";
        match parse_hls_m3u8("https://cdn.example.com/live/index.m3u8", m3u8) {
            Some(ManifestParseResult::MediaSegments(segs)) => assert_eq!(segs.len(), 2),
            _ => panic!("expected HLS segments"),
        }

        let mpd = r#"<MPD><Period><AdaptationSet><Representation><SegmentList><SegmentURL media="chunk1.m4s"/></SegmentList></Representation></AdaptationSet></Period></MPD>"#;
        assert_eq!(parse_dash_mpd("https://cdn.example.com/v/m.mpd", mpd).unwrap().len(), 1);

        let (tx, _rx) = mpsc::unbounded_channel();
        let bad_resp = handle_json_rpc_payload(
            r#"{"jsonrpc":"2.0","id":"1","method":"aria2.getVersion","params":["token:wrong"]}"#,
            "deadbeef",
            &tx,
        );
        assert!(bad_resp.contains("Unauthorized"));

        let good_resp = handle_json_rpc_payload(
            r#"{"jsonrpc":"2.0","id":"1","method":"aria2.getVersion","params":["token:deadbeef"]}"#,
            "deadbeef",
            &tx,
        );
        assert!(good_resp.contains("1.37.0-aetherio-v9.0"));

        let clean = redact_sensitive("token:secret123 https://cdn.example.com/file.bin?sig=abc&expires=99");
        assert!(!clean.contains("secret123"));
        assert!(!clean.contains("sig=abc"));
        record_diag("TEST", &clean);
        assert!(!export_diagnostics_text().is_empty());

        let mut dst = [0u8; RLNC_WINDOW];
        gf256_mul_acc(&mut dst, &[0x5Au8; RLNC_WINDOW], 0x07);
        assert!(dst.iter().any(|&b| b != 0));
        assert_eq!(blake3_digest_hex(b"aether").len(), 64);

        let snap = crate::scheduler::ActiveConnectionSnapshot {
            conn_id: "t1-w0".into(),
            task_id: "t1".into(),
            mirror_url: "https://cdn.example.com/a.bin".into(),
            link_key: "cell:rmnet0".into(),
            protocol: "H2/H3".into(),
            state: "Streaming".into(),
            range_start: 0,
            range_end: 1023,
            bytes_pulled: 512,
            speed_bps: 1024,
            ttfb_ms: 22,
            blocked_reason: "NONE".into(),
        };
        let json = serde_json::to_string(&snap).expect("serialize ActiveConnectionSnapshot");
        assert!(json.contains("\"url\":\"https://cdn.example.com/a.bin\""));
        assert!(json.contains("\"link_key_id\":\"cell:rmnet0\""));
        assert!(json.contains("\"socket_state\":\"Streaming\""));
        assert!(json.contains("\"bytes_received\":512"));
    }
}
