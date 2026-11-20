# AetherIO v9.0 — Quad-Link Dual-Engine Download Accelerator for Android

AetherIO is a high-throughput, zero-merge Android download engine featuring **two individually toggleable engines** (**Pure Rust `libaether_core.so` arm64-v8a** + **Kotlin OkHttp Turbo**), **Quad-Link Multi-Network Bonding** (`Wi-Fi 1 + Wi-Fi 2 + Mobile 5G/LTE + USB Tethering/Ethernet`), **Anti-Dip Sustained Speed Architecture**, and **Motorsport Bike/Vehicle Live Speedometers**.

---

## Key Capabilities

### 1. Dual-Engine A/B Switchboard (Rust Native + Kotlin OkHttp Turbo)
Switch between or benchmark **both** engines on the same device:
- **Pure Rust Core (`libaether_core.so`, `arm64-v8a`)**:
  - Persistent pooled `wreq` (BoringSSL Chrome TLS/H2 fingerprint emulation) + `quinn`/`h3` HTTP/3 QUIC 0-RTT.
  - **Decoupled Async Disk-Writer Task** (`mpsc::channel(16)`) with 512 KiB slab coalescing (`BoundedSlabPool`, 12 MiB / 24 slabs) and batched `fdatasync` + `AETHLDG2` checkpointing.
  - Zero-JNI 4 KB `memfd` shared telemetry page polled at 4 Hz (0 Hz in background).
  - **True Atomic Live Range Bisection** (`ActiveRangeState`) so fast workers split lagging ranges mid-flight.
- **Kotlin OkHttp Turbo Engine**:
  - Persistent per-link `OkHttpClient` pools (`Dispatcher(maxRequests = 64, maxRequestsPerHost = 32)`, `ConnectionPool(32, 5m)`, 4 MiB `SO_RCVBUF`, `TCP_NODELAY`).
  - Lock-free positional `FileChannel.write(buf, offset)` (`pwrite`) with 512 KiB direct slab coalescing.
  - Live atomic range bisection up to 16 parallel streams + HTTP/SOCKS5h Companion Wi-Fi 2 Relay support.

> **Note on AI Studio Preview vs. GitHub Release APKs**:
> AI Studio's cloud preview sandbox does not include `cargo-ndk`, so preview APKs run the full **Kotlin OkHttp Turbo** engine. The **GitHub Actions Release APK** (`arm64-v8a`) compiles and bundles `lib/arm64-v8a/libaether_core.so` **alongside** Kotlin OkHttp so you can run and benchmark **both** engines natively!

---

### 2. Quad-Link Multi-Network Bonding (`Wi-Fi 1 + Wi-Fi 2 + Mobile 5G + USB`)
Bond up to **4 simultaneous network pipes** without root:
1. **Wi-Fi 1 (`wifi:wlan0`)** — Primary Wi-Fi interface.
2. **Wi-Fi 2 Boost (`wifi2:`)** — Configured directly inside AetherIO via **Quad-Link Wi-Fi 2 Connector**:
   - **Direct Wi-Fi 2 (Dual-STA)**: Joins a 2nd SSID via Android `WifiNetworkSpecifier` with nearby Wi-Fi scan, band selection, and automatic **Single-STA Displacement Detection & 1-Tap Rollback**.
   - **Companion Wi-Fi 2 Relay (Any Phone, Zero Root)**: Point AetherIO at any local SOCKS5h or HTTP CONNECT proxy running on a 2nd phone or dual-WAN router connected to Wi-Fi 2 to get true simultaneous 2× Wi-Fi + 5G + USB bonding even on single-radio phones.
3. **Mobile 5G/LTE (`cell:rmnet0`)** — Retains an active cellular `Network` lease via `ConnectivityManager.requestNetwork` even while Wi-Fi is default.
4. **USB Tethering / USB-C Ethernet (`eth:` / `usb:`)** — Automatically detects hot-plugged USB-C Gigabit Ethernet adapters and tethered Android devices (`TRANSPORT_USB` API 31+ / `TRANSPORT_ETHERNET`).

---

### 3. Anti-Dip Sustained Throughput Engineering
Eliminates the classic "400 Mbps spike → 3–4 Mbps drop" and "Wi-Fi ↔ Mobile flip-flopping" issues:
- **Warm Keep-Alive Connection Pools**: Sockets stay warm across 16–32 MiB BDP segments instead of re-handshaking TLS/TCP Slow Start.
- **Non-Blocking Disk I/O**: Eliminated global write locks and 500 ms blocking `fsync` stalls that previously froze network readers and tripped link circuit breakers.
- **Hysteresis Anti-Dip Watchdog**: 4.5s stall watchdog with 3-strike non-immediate circuit breaker tripping and 6s recovery cooldown prevents false Wi-Fi ↔ 5G oscillation.
- **Live Range Bisection**: Idle workers bisect slow in-flight ranges dynamically to keep aggregate speed pegged near peak.

---

### 4. Three Selectable Bike / Vehicle Speedometer Layouts
Switchable anytime from **Engine Settings** or the quick-cycle chip directly under the Aggregate Throughput card:
- **Superbike 2×2 Cockpit Grid**: 260° radial motorsport dials with crimson redline zone (`84%–100%`), tapered spring-damped needle, peak-hold + AVG markers.
- **Auto Sport Horizontal Dash**: Compact radial tachometer paired with digital `LIVE / MAX / AVG` readout chips.
- **Compact Racing Tach Strip**: 20-segment LED shift-light RPM bars for maximum transfer-list density.

Every gauge displays **LIVE speed**, **MAX (session peak)**, **AVG (active average)**, **worker count**, **interface/capacity**, and **bytes pulled** for Wi-Fi 1, Mobile 5G, Wi-Fi 2, and USB/Eth.

---

### 5. Zero-Root Permissions
All features work without root. AetherIO declares and guides you through granting only standard Android permissions:
- `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
- `NEARBY_WIFI_DEVICES` (`neverForLocation`, API 33+) / `ACCESS_FINE_LOCATION` (`maxSdkVersion="32"`) for optional nearby Wi-Fi 2 scanning
- `POST_NOTIFICATIONS` (API 33+) & `FOREGROUND_SERVICE_DATA_SYNC` for live speed notifications
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to prevent OEM Doze throttling (HyperOS / OneUI / ColorOS)

---

## Automated GitHub Actions Releases & Unique Package Names

Every push to `main` (or manual `workflow_dispatch`) triggers `.github/workflows/build-release.yml`, which:
1. Installs Rust + `aarch64-linux-android` + `cargo-ndk` + Android NDK and runs `cargo test --lib`.
2. Compiles `app/src/main/jniLibs/arm64-v8a/libaether_core.so` in `--release` mode.
3. Runs `gradle :app:testDebugUnitTest` and `gradle :app:lintVitalRelease`.
4. Assigns a **unique APK package name per build** (`com.aistudio.aetherio.b<run_number>`) via `-PciApplicationId=...` so every release can be installed side-by-side cleanly while preserving `namespace = "com.example"` for JNI bindings.
5. Verifies `lib/arm64-v8a/libaether_core.so` is present inside the APK, computes SHA-256, builds categorized Release Notes (**Added**, **Upgraded & Enhanced**, **Fixed**, **Changed**, **Removed**), and publishes the APK + `.sha256` to **GitHub Releases**.

---

## Local Development

```bash
# 1. Run Rust core host unit tests
cd rust/aether-core && cargo test --lib

# 2. (Optional) Compile Rust libaether_core.so for arm64-v8a
cargo ndk -t arm64-v8a --platform 24 -o ../../app/src/main/jniLibs build --release

# 3. Run Android unit & Robolectric tests
cd ../.. && gradle :app:testDebugUnitTest

# 4. Build debug or release APK
gradle :app:assembleDebug
```

See [`CHANGELOG.md`](CHANGELOG.md) for the complete version history.

---

## Third engine: aria2-next (Rayburst)

CI downloads the official `aria2-next` Android arm64 executable (v3.1.1, SHA-256
verified) into `app/src/main/assets/aria2-next`. The app extracts it to
app-private storage, starts it as a loopback-only JSON-RPC daemon with a per-boot
secret, and drives downloads via `aria2.addUri` / `aria2.tellStatus`. Enable it
with the **aria2-next Engine (Rayburst)** toggle in Engine settings or choose it
as a forced backend.

## Throughput & stability fixes in this fork

- Both engines now **scale down** workers when adding sockets lowers goodput —
  no more permanent 64-lane congestion collapse (70 Mbps spikes / 25 Mbps avg).
- Rust and OkHttp lane caps reduced to 24/20 with thermal-aware ceilings.
- Exponential backoff on connection failures replaced the 120 ms hammer loop.
- Stale 30 s TTFB samples are ignored by both adaptive governors.
- Wi-Fi links are accepted with INTERNET + a usable IP even when OEM ROMs never
  set `NET_CAPABILITY_VALIDATED`, restoring Wi-Fi + mobile bonding.
- Startup SIGSYS crash (seccomp-blocked `io_uring_setup`, syscall 425) removed.
