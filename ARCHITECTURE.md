# AetherIO Architecture (as built)

This document describes the **actual** code, not an idealised design. It is the
5-minute onboarding entry point. Product overview lives in `README.md`; verified
change history is in `CHANGELOG.md`.

## Canonical constants (single source of truth)

| Constant | Value | Defined in |
| --- | --- | --- |
| Rust I/O slab | 256 KiB | `rust/aether-core/src/storage_and_telemetry.rs:15` `SLAB_BYTES` |
| Rust slab pool | 64 slabs = 16 MiB | `storage_and_telemetry.rs:16` `MAX_SLABS` |
| Kotlin I/O slab | 256 KiB | `app/.../engine/SlabBufferPool.kt:12` |
| Kotlin slab pool | 96 slabs = 24 MiB ceiling | `SlabBufferPool.kt:13` |
| Worker cap (both engines) | 128 | `scheduler.rs:9`, `DownloadModels.kt:8` |
| Default workers | 32 | `DownloadModels.kt:210` |
| Range sizes | 256 KiB → 4 MiB by link speed | `scheduler.rs:20-32`, `LinkScheduler.kt:79-88` |
| Ledger digest granularity | 256 KiB I/O slabs | `ledger.rs:18` `SLAB_DIGEST_GRANULARITY` |
| Telemetry page | 4096 B, fixed byte offsets (ABI with Kotlin) | `storage_and_telemetry.rs:43-69` |

> H3/QUIC is **probe-only** (16 KiB Stream-0). All bulk bytes use wreq/H2 on
> Rust and HTTP/1.1 sharded sockets on Kotlin. Do not assume H3 carries payload.

## End-to-end data flow

```
 Kotlin UI (ui/screens/*, ui/components/*)
        |  NewDownloadSheet / AetherViewModel
        v
 AetherDownloadEngine ............................. THREE-way router
   |-- Rust  --- AetherNativeBridge (JNI) -------> nativeStartDownload
   |      |                                          |
   |      |  memfd fd -> MemfdTelemetryReader -------+ (0-JNI, 4 Hz read)
   |      v                                          v
   |  rust/engine.rs  Stream-0 probe (wreq; H3 16 KiB probe only)
   |      |  scheduler.rs (bisect / EWMA / force-all / circuit breakers)
   |      v  DiskWriteJob x N -> BoundedSlabPool(64 x 256 KiB) -> pwrite
   |      |                      -> AETHLDG2 ledger (fdatasync -> msync)
   |-- Kotlin OkHttp -> KotlinOkHttpTransferRunner -> SlabBufferPool(96 x 256 KiB)
   |      |  LinkScheduler (mirrors Rust maths)      -> FileChannel.write(pos)
   |      -> per-link sharded OkHttpClient (HTTP/1.1, dispatcher 128/128, pool 64)
   |-- aria2-next  -> Aria2NextEngine -> loopback JSON-RPC 127.0.0.1:16800

 NetworkMonitor -> 4 link handles (wifi, wifi2, cell, eth/usb) -> Rust & Kotlin
 TelemetryLedger <- memfd page + Kotlin snapshots -> DiagnosticsCenter / UI
```

## Download execution (vertical slice: "100 MB on Wi-Fi + 5G")

1. **Enqueue:** `AetherDownloadEngine.enqueue()` (`AetherDownloadEngine.kt:114`)
   builds `DownloadEntity`, picks a backend via `StoragePlacementHelper.selectBackend()`.
2. **Route:** `executeDownload()` (`:367`) re-pushes all link handles with
   `networkMonitor.syncAllToNative()` before native dispatch; flips status to
   STREAMING immediately.
3. **Probe:** each engine issues a tiny `Range` probe to learn total size,
   `Accept-Ranges`, ETag, filename. A server answering 200 to a Range request is
   rejected (would corrupt offsets).
4. **Segment claim:** Rust uses `AetherLedger` states + `ActiveRangeState` CAS
   bisection (`engine.rs:824-867,1518-1591`); Kotlin uses `bitChars` +
   `pendingSegments` + `tryStealBisectedRange()`.
5. **Read:** `source.read(slab)` / `resp.bytes_stream()` into 256 KiB slabs;
   per-chunk byte accounting is coalesced to one update per slab flush.
6. **Write:** positional `pwrite`/`FileChannel.write(buf, offset)` — no global
   write mutex, no `.part` merge. Short writes loop until the buffer is drained.
7. **Verify:** BLAKE3 per slab into the ledger; a single sequential SHA-256
   frontier hashes bytes in order (seed from the ledger frontier on resume).
8. **Checkpoint:** ledger persisted every 16 MiB / 2 s after a data `fdatasync`.
9. **Complete:** final SHA-256 compared to `expectedSha256` when supplied;
   MediaStore/SAF finalisation; status COMPLETED.

## Module map (Rust crate)

```
 adapters/      jni_bridge.rs (JNI boundary)      aria2_rpc.rs (loopback JSON-RPC stub)
 orchestration/ engine.rs     (task lifecycle + worker fan-out + writer)
 domain/        scheduler.rs (pure policy)        ledger.rs     cas.rs
 services/      transport.rs (DNS, link cache, wreq clients, H3 probe)
 foundation/    storage_and_telemetry.rs (slab pool, memfd page, I/O helpers)
                diagnostics.rs (feature flags + redacted log ring)
```

Module dependency graph is a DAG: adapters → engine → domain/services →
foundation. `scheduler.rs` is pure (no I/O, no global state).

## Where to start (5-minute path)

1. Read this file and the constants table.
2. `AetherDownloadEngine.kt:31-66` — engine routing and collaborators.
3. Pick ONE backend and read one path end-to-end:
   - Rust: `lib.rs` → `engine.rs::start_task` → `scheduler.rs`.
   - Kotlin: `KotlinOkHttpTransferRunner.kt::run` → `LinkScheduler.kt`.
4. Read `rust/aether-core/src/lib.rs` tests (lines 55-276) — they are the
   executable spec for the numeric invariants.
5. See `CONTRIBUTING.md` for build/test commands.

## Known limitations (honest)

- The Rust bulk path binds by source IP, not by Android `Network` netId: true
  Wi-Fi+cellular bonding of Rust bytes needs a custom connector using
  `android_setsocknetwork`; Kotlin's OkHttp path binds correctly via
  `Network.getSocketFactory()` + `Network.getAllByName()`.
- Feature flags for H3 bulk, io_uring and aria2-RPC control are advertised but
  not wired to payload transfer.
- Telemetry page is a global shared page; concurrent tasks overwrite counters.
- `rlnc.rs` (GF(2^8) coding) and `AltSvcHintCache` are implemented but not used
  by the data plane.
