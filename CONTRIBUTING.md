# Contributing to AetherIO

Read `ARCHITECTURE.md` first (5 minutes). This project has three download
engines, a stable-Rust JNI core, and mirrored scheduler maths in Rust and Kotlin.

## Toolchain (exact versions from CI)

- Stable Rust with the `aarch64-linux-android` target + `cargo-ndk`
- JDK 21
- Gradle 9.3.1 (CI installs via `gradle/actions/setup-gradle@v4`)
- Android NDK 27.2.12479018
- Android SDK platform 36

## Build & test locally

```bash
# Rust host unit tests (the executable numeric spec)
cd rust/aether-core && cargo test --lib && cd ../..

# Compile the native library for arm64-v8a
cd rust/aether-core && ./build_android.sh && cd ../..
#   or exactly like CI:
#   gradle :app:buildRustCore -PrequireRustBuild=true

# Kotlin unit + Robolectric tests, lint
gradle :app:testDebugUnitTest -PskipRustBuild=true
gradle :app:lintVitalRelease -PskipRustBuild=true

# Full release APK (build happens in CI on every push)
gradle :app:assembleRelease -PciAbiFilter=arm64-v8a -PskipRustBuild=true
```

Do not build the Android APK locally unless necessary — use GitHub Actions;
local builds need `debug.keystore` injected by AI Studio and can differ from CI.

## CI gates every push must pass

Defined in `.github/workflows/build-release.yml`:

1. aria2-next arm64 binary downloaded and SHA-256 verified.
2. `cargo test --lib`.
3. `libaether_core.so` built with `RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"`
   and **16 KB ELF LOAD alignment** asserted via `readelf`.
4. `libc++_shared.so` bundled; every `DT_NEEDED` dependency checked.
5. Kotlin unit tests + `lintVitalRelease`.
6. Release APK built with `-PciAbiFilter=arm64-v8a`; both `.so` files verified
   inside the APK; SHA-256 published with the release.

## Feature checklist (important for consistency)

1. **Mirrored maths:** changing `scheduler.rs` (governor, segment sizing, force-all)
   requires the same change in `app/.../engine/LinkScheduler.kt`, and vice versa.
2. **Telemetry ABI:** changing `TelemetryPage` field order/offsets in
   `storage_and_telemetry.rs` requires the same change in
   `app/.../engine/MemfdTelemetryReader.kt`. Byte offsets are a wire contract.
3. **Ledger format:** bump `LEDGER_VERSION` only with a migration path. The
   AETHLDG2 v2 format is documented in `ledger.rs:35-46` and mirrored in
   `AetherBinaryLedger.kt`.
4. **JNI symbols:** every `#[unsafe(no_mangle)] extern "system"` function in
   `jni_bridge.rs` must match an `external fun` in `AetherNativeBridge.kt`.
   Never panic across the JNI boundary — wrap work in `catch_unwind` and return
   empty/zero values on failure.
5. **Docs in the same PR:** update `README.md` and `CHANGELOG.md` when a numeric
   constant or capability changes. See the canonical constants table in
   `ARCHITECTURE.md`.
6. **Commits:** CI categorises release notes from Conventional Commit prefixes:
   `feat:`, `fix:`, `perf:`, `refactor:`, `remove:`.

## Adding a feature

- Prefer extending an existing engine over adding a new one.
- New native functionality: expose a typed JNI function, add a `Safe` wrapper in
  `AetherNativeBridge.kt`, and surface state through `TelemetrySnapshot` or
  `DiagnosticsCenter` rather than ad-hoc logging.
- New scheduler rule: add a pure function in `scheduler.rs` with unit tests in
  `lib.rs`, mirror it in Kotlin, then wire it into `engine.rs`.
- Always add a regression test. `app/src/test/java/com/example/` holds the
  current Robolectric/JUnit suites.

## Benchmarks

`scripts/bench_device.sh` exists but its hardcoded package name must be updated
to the current CI package (`com.aistudio.aetherio.b<run_number>`) before use.
No benchmark results are published from CI yet; do not claim speed improvements
without measurements.
