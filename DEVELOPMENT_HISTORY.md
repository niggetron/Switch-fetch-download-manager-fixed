# AetherIO Development History — Complete Record

This file records every bug found, fix applied, feature added and improvement made
during the development of this fork, **from the first commit to the latest**.
It deliberately **excludes all aria2-next-related work** — that engine has its own
dedicated effort. For architecture, see `ARCHITECTURE.md`; for contributor
workflow, see `CONTRIBUTING.md`; for verified per-release notes, see `CHANGELOG.md`.

Base of the fork: upstream `8d5ab5c` ("build: bump version to 9.1 and update 16KB
alignment check"). All work below was pushed to this repository and validated by
GitHub Actions (no local builds).

---

## Phase 0 — Restoring a bootable, bundleable app (commits `7e19000`–`dbb984e`)

### Bugs found
1. **Native library missing at runtime**: `libaether_core.so` requires `libc++_shared.so`, which was never bundled. `System.loadLibrary()` failed with `dlopen failed: library "libc++_shared.so" not found`, so the app always showed "Rust libaether_core.so not bundled" and fell back to Kotlin.
2. **Startup SIGSYS crash (P0)**: `probe_io_uring_supported()` called raw syscall 425 (`io_uring_setup`). Android seccomp delivers SIGSYS (not EPERM), killing the process ~1 s after launch. Confirmed in user logcat (`fault addr`, `signal 31 (SIGSYS)`, `Cause: seccomp prevented call to disallowed arm64 system call 425`, `nativeEngineVersion+36`).
3. **Native init on the main/startup path**: telemetry construction and memfd attachment called native code before the first frame, so any native problem took down the whole app.

### Fixes
- CI copies `libc++_shared.so` from the NDK into `jniLibs/arm64-v8a/` and verifies every `DT_NEEDED` dependency plus the final APK contents.
- Replaced the raw syscall probe with a constant `false` (the engine uses `fallocate + pwrite + fdatasync` on Android).
- Deferred native warmup, memfd attachment, badge refresh and aria2 daemon startup into a background coroutine; wrapped every native call in `runCatching`.

---

## Phase 1 — Release correctness and truthful UI (commits `9d50bf9`–`f73734a`)

### Bugs found
- CI was built from a stale local copy; the first builds were v6-based rather than upstream v9.1.
- GitHub Releases repeated the same historical `## [9.1]` changelog block on every run.
- `libc++_shared.so` was built into CI but not `jniLibs` declared in Gradle; `jniLibs.srcDirs` and `noCompress` were missing.
- Rust core used nightly-only APIs (`allocator_api`, `DropGuard`, `conjure_zst`, `as_mut_unchecked`) so it could not cross-compile in CI.

### Fixes / features
- Rebased the fork onto actual upstream `8d5ab5c`; original repo left untouched (verified repeatedly via `git ls-remote --heads upstream`).
- Release notes regenerated from `git log <prev-tag>..HEAD` grouped into Added / Fixed / Upgraded & Enhanced / Changed / Removed with a full compare link — no repeated text.
- Added explicit `jniLibs.srcDirs`, `noCompress` for the native/asset binaries, ABI filter, 16 KB ELF alignment check, native dependency scan, signature-verification step.
- Rewrote the Rust core on stable Rust: heap-backed bump arena with RAII cursor guard, plain `Vec` + `io_uring`-free commit path, unit tests for arena/rollback.
- Added unique per-build package name (`com.aistudio.aetherio.b<run>`) and signed APK with checksum.
- Corrected version drift and stale comments across README/CHANGELOG/source banners.

---

## Phase 2 — Throughput, dashboards and cross-engine work (commits `01ad29d`–`9ead467`)

> Engine-agnostic items are recorded here; engine-specific work appears in
> Phase 3 (OkHttp), Phase 4 (Rust) and the separate aria2-next effort.

### Features added
- **Cloudflare-style Speed dashboard tab** with animated goodput charts, status tiles, quality heuristic, a geo-located world map and distribution cards.
- **Shizuku privilege prompt** on the dashboard, with grant/start/get actions.
- **Share-sheet intake** (`ACTION_SEND` text/plain) queues shared URLs.
- **Wake locks**: `PARTIAL_WAKE_LOCK` in the FGS and WorkManager fallback (previously only a Wi-Fi lock existed, so downloads stalled with screen off).
- **Private backups**: explicit exclusions for Room DB, DataStore, ledgers, staging and daemon state (empty rules previously backed up signed URLs and proxy credentials).
- **Correct `FileProvider` path** `Download/` (was `Downloads/`), fixing Open/Share for every non-SAF download.

### Bugs fixed
- **`HttpAuthExpiredException`/403/410** handling existed but had several gaps; Range-ignoring servers returning HTTP 200 were accepted and written at the segment offset, corrupting files — rejected in both engines.
- **Unknown-length Rust responses** were truncated to the 16 KiB probe snippet and reported COMPLETED — now stream to EOF with unknown size.
- **Bisection COMMITTED race**: a split segment was marked `COMMITTED` while the stolen half was still downloading → crash/resume skipped unwritten bytes. Kotlin now tracks outstanding parts and commits only when all are done; Rust fix is planned (see Phase 4).
- **Privacy/security**: removed committed CI logs containing the signing password; moved `workflow_dispatch` inputs from inline shell interpolation to `env:` (command-injection fix).
- **Dashboard honesty**: removed fabricated upload (`instantBps * 0.06`), latency-from-throughput, stability-as-jitter, inferred packet-loss, hard-coded quality stars and fake India map pins; added real host/IP/city/country/ISP display with three geo providers and explicit "unavailable" states.
- **Compose**: shared-transition opt-ins, `Path.lineTo` coordinate fix, distinct nav icons, honest engine capability labels.
- **128-lane ceilings** raised across engines with thermal and per-host governors; default workers raised from 16 to 32.
- **Per-host connection-limit learning** (`HostLimits`) adapted from Mister Download Manager: two refusals + 10-minute decay prevent opening 128 sockets on a 2-connection server.
- **MDM small-file ramp**: one 64 KiB piece per connection initially, avoiding duplicate requests on small files.
- **`Retry-After` on 429/503** honored as a floor (≤10 minutes).
- **Slab pool** bounded to 24 MiB direct memory with heap fallback so 128 workers cannot exhaust native memory.
- **Native resume API** added in Rust (`nativeResumeDownload`).

---

## Phase 3 — OkHttp engine deep optimisation (commits `7bd21e7`–`2c1fa2c`)

A dedicated 2-agent investigation (deep code audit + OkHttp/reference-engine research) produced these changes:

### Bugs found
- Governor raised `targetWorkers` to 96–128 while only `boostedDesired` (e.g. 32) coroutines existed → advertised lanes never materialised.
- Per-chunk telemetry did 4–6 contended atomic RMWs per 8–64 KiB socket read.
- Parked workers scanned the entire bitfield (up to 16 384 entries) under the global claim mutex every 150 ms.
- 150 ms connection snapshots allocated ~640 objects/sec at 128 workers.
- `okHttp` dispatcher caps (128/128) were inert because all calls use synchronous `execute()`.
- OkHttp's own `retryOnConnectionFailure` silently re-issued range requests behind our backoff.
- `Retry-After` sleeps kept the stall watchdog alive, tripping the circuit breaker for a compliant server.
- `clientPool` keyed by `netHandle` never evicted → OkHttp clients, pools, threads and FDs leaked on every network rotation.
- Bisection failure left the segment permanently un-committable and allowed overlapping writes.
- Flush threshold (448 KiB) was unreachable with 256 KiB slabs; per-shard `ConnectionPool(8)` contradicted the base pool (64).

### Fixes
- Per-slab byte accounting replaced per-chunk atomics; telemetry throttle 150 → 450 ms; O(1) `pendingSegments` counter replaced the bitfield scan.
- Worker pool now launches enough coroutines for the full ramp range; surplus workers park cheaply until the governor raises the target.
- Full-segment rollback on bisection failure: cancel sibling ranges, restore the full extent, reset to PENDING.
- Watchdog cancelled before `Retry-After` sleep; `LinkStallException` classified as a network flap.
- Built-in OkHttp retries disabled; 30 s timeouts; `callTimeout=0`.
- Stale per-handle clients evicted on rotation; flush threshold aligned with the real slab size.
- `Accept-Encoding: identity` and strict 206 requirement for ranged workers retained/verified from research.

---

## Phase 4 — Rust engine audit, correctness and onboarding (commits `6ddccc5`–`9265109`)

Six independent specialist agents (architecture/HLD-LLD, scheduler/planner, transport, storage/verifier, JNI/reliability, docs/onboarding) audited the entire Rust crate.

### Bugs found
- **CRITICAL resume hash**: `ContiguousSha256Verifier` started at offset 0 while the ledger carried a resume frontier; a fully-resumed file produced the SHA-256 of an empty string yet was marked COMPLETED.
- **JNI FFI-abort class**: `env.new_string(...).expect(...)` sat outside `catch_unwind`; with `panic = "unwind"` a JNI allocation failure would panic across `extern "system"` and abort the Android process.
- JNI capped workers/thermal at 64 while Kotlin/Rust ceilings were 128.
- DNS "3-way race" used `tokio::select!` which returns on first *completion* (often `Err`) then fell through to a fourth unbound lookup; DNS replies were unvalidated.
- AETHLDG2 slab-digest array sized for 512 KiB but indexed at 256 KiB → half the digests silently dropped.
- Scheduler agents found two P0 corruption bugs (stolen-half failure orphans bytes; one half marks the whole segment Done) and proved `absorb_empty_right_of` is unreachable dead code.
- Transport agent confirmed bulk wreq sockets bind only by source IP, not to the Android `Network` netId — true Rust multipath bonding is not yet implemented.
- Docs agent found constants wrong nearly everywhere (12 MiB vs 16 MiB, 24/64 vs 128, H3 advertised as a bulk transport).

### Fixes
- Verifier now has `with_offset()`/`seed_prefix()` and is seeded from the ledger frontier at resume; final SHA-256 covers the whole file.
- JNI allocation failures return empty/zero values instead panicking; caps raised to 128.
- DNS race uses `futures_util::future::select_ok` (first success wins; all failures → unbound fallback).
- `SLAB_DIGEST_GRANULARITY = 256 KiB` matches the writer; binary format remains AETHLDG2 v2-compatible.
- Stale comments and version banners corrected; module-level docs added to `scheduler.rs`, `storage_and_telemetry.rs`, `jni_bridge.rs`, `engine.rs`, `lib.rs`.
- **New developer onboarding**: `ARCHITECTURE.md` (canonical constants, data-flow diagram, vertical slice, module map, 5-minute start path, honest limitations) and `CONTRIBUTING.md` (toolchain, CI-mirroring commands, consistency checklist, Conventional Commits).

---

## Bugs found but NOT yet fixed (carried forward)

These were documented by the specialist audits and remain on the roadmap:

1. **Rust bulk socket `Network` binding (P0)**: wreq 0.16 exposes no pre-connect fd hook; a custom hyper connector using `android_setsocknetwork` or a JNI `Network.bindSocket` callback is required. Kotlin OkHttp already binds correctly.
2. **Rust scheduler planner rewrite (P0)**: adopt MDM/aria2-next claim/commit planning with speed-weighted, 4096-aligned cuts, takeover/extend and endgame racing; current CAS bisection has orphaned-half failure and premature-Done bugs.
3. **Bisection failure handling in Rust** (same family): restore full segment on failure; do not let one half mark the whole segment Done.
4. **Telemetry page is global**: concurrent tasks overwrite each other's counters; seqlock in unused `_pad2` bytes proposed.
5. **Non-atomic ledger rewrite** in Rust and Kotlin; temp-file + atomic rename proposed.
6. **Feature flags with no payload effect**: H3 bulk, io_uring probe, aria2-RPC control, RLNC, Alt-Svc, HLS/DASH segment scheduling.
7. **Automation gaps**: no `Cargo.lock`, no instrumented tests, no benchmark harness; `scripts/bench_device.sh` has a hard-coded package name.
8. **Credentials**: PAT in git remotes and release signing key/password are still live; user elected to rotate after project completion. Hard-coded password in CI and committed log were removed.

---

## Summary of shipped improvements by category

- **Correctness**: resume hash, Range-200 rejection, unknown-length streaming, bisection commit safety (Kotlin), FFI panic safety, DNS race, digest granularity, FileProvider path.
- **Speed**: 128-lane ceilings with host/thermal governors, per-slab telemetry coalescing, O(1) claim checks, full-ramp coroutine pool, small-file ramp, `Retry-After`, no built-in OkHttp retries, reduced atomics/memsets, adaptive link EWMA.
- **Stability**: wake locks, stale-client eviction, watchdog/backoff fix, startup native deferral, native resume API, full-segment rollback on failure.
- **Security/privacy**: private backups, removed committed secrets/log, CI injection fix, identity encoding, Share intents.
- **DX/docs**: commit-generated release notes, `ARCHITECTURE.md`, `CONTRIBUTING.md`, module docs, honest version/feature claims, charts and release notes kept in sync.

All entries above were implemented, pushed and built on GitHub Actions. No benchmark numbers are claimed: performance work is justified by code-path mechanics, and a measurement harness is still a roadmap item.
