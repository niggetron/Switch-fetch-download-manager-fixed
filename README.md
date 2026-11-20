# AetherIO Omega — Switch Fetch Download Manager

A high-speed Android download manager with 2 MB F2FS-aligned extents, Wi-Fi + 5G
multipath scheduling, work-stealing tail hedges, CAS chunk deduplication, and a
stable-Rust native core (`libaether_core.so`).

## What was broken (and what this repo fixes)

| Problem | Fix |
| --- | --- |
| The release workflow never compiled the Rust crate, so `libaether_core.so` was never bundled and the app always showed "libaether_core.so not bundled". | `.github/workflows/build-release.yml` now installs an Android NDK, stable Rust + `aarch64-linux-android`, builds the `.so` with `cargo-ndk`, verifies its JNI symbols, verifies it is inside the APK, and uploads it as an artifact. |
| The Rust source used nightly-only APIs (`allocator_api`, `DropGuard`, `conjure_zst`, `UnsafeCell::as_mut_unchecked`) that cannot be cross-compiled in CI. | Rewritten on **stable Rust**: heap-backed bump arena with an RAII cursor guard, plain `Vec` + `io_uring` commit path. Unit tests cover the arena and rollback guard. |
| The native functions existed but nothing in the app ever called them. | Kotlin now calls native BLAKE3 for CAS chunk hashing, native telemetry on every committed extent, and the Rust earliest-arrival scheduler for multipath link selection. Telemetry screen shows live native arena/committed bytes. |
| OkHttp silently capped the engine at 5 connections per host. | Dedicated `ConnectionPool(16, 10 min)` and `Dispatcher(maxRequestsPerHost = 32)` unlock all 8 workers. Also raised timeouts and read buffer size, and bumped slabs to 512 KB. |
| Slab accounting drifted when all slabs were checked out. | Overflow now uses a heap buffer so direct-buffer accounting stays accurate. |
| No jniLibs declaration in Gradle. | `sourceSets.main.jniLibs.srcDirs("src/main/jniLibs")` so the CI-built `.so` is packaged in every APK. |

## Architecture

- **Transport (Kotlin + OkHttp):** HTTP/2, byte-range segments, per-`Network`
  socket binding for real Wi-Fi + cellular aggregation, cancellation/resume.
- **Native core (Rust, `libaether_core.so`):**
  - BLAKE3 SIMD hashing for the content-addressed chunk index
  - GF(2^8) RLNC coding helpers (`nativeGf256MulAcc`)
  - Earliest-arrival multipath scheduler (`nativeSelectLink`)
  - 2 MB F2FS extent math and a bounded 16 MB bump arena
  - Cache-line-isolated atomic telemetry page

The native core handles the compute-heavy data-plane layers; OkHttp remains the
transport because it can bind sockets to individual Android `Network` handles,
which is what makes multipath aggregation actually work.

## Building

Push to `main` (or run the workflow manually) — the workflow produces an
`arm64-v8a` release APK on the Releases page. Every release gets a unique
applicationId (`com.aistudio.aetherio.xkqmzp.r<run number>`) so builds install
side by side, and the signed APK is verified with `apksigner` before publishing.

Local native build (requires stable Rust, cargo-ndk and an Android NDK):

```bash
./rust/aether-core/build_android.sh
gradle :app:assembleRelease -PciAbiFilter=arm64-v8a
```

Without the `.so`, the app still runs on the Kotlin OkHttp fallback; the
Telemetry screen will label it as "not bundled".
