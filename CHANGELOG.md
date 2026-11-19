# Changelog

All notable changes to **AetherIO** are documented in this file. The automated GitHub Actions release pipeline embeds the latest release section below directly into every GitHub Release.

## [9.0] - Quad-Link Superbike Bonding, Anti-Dip Turbo Engine & CI Auto-Release

### Added
- **Quad-Link Multi-Network Bonding (Wi-Fi 1 + Wi-Fi 2 + Mobile 5G/LTE + USB Tethering/Ethernet)**: Simultaneously stripe downloads across up to 4 active links (`wifi:`, `wifi2:`, `cell:`, `eth:`/`usb:`).
- **In-App Dual-Band Wi-Fi 2 Connector (`DualWifiConnectorSheet`)**:
  - Live chipset probe for Android Dual-STA Multi-Internet, Dual-STA Local-Only, 5 GHz, and 6 GHz (Wi-Fi 6E/7).
  - Direct Wi-Fi 2 join via `WifiNetworkSpecifier` with nearby Wi-Fi scanner, band preference (`Auto`, `5 GHz`, `2.4 GHz`, `6 GHz`), and automatic **Single-STA Displacement Detection & 1-Tap Rollback**.
  - **Companion Wi-Fi 2 Relay (SOCKS5h / HTTP CONNECT)**: Lets ANY Android phone without Dual-STA hardware bond a second Wi-Fi network alongside Wi-Fi 1, 5G, and USB without root.
  - **USB Tethering & USB-C Ethernet Detector**: Automatically monitors `TRANSPORT_USB` (API 31+) and `TRANSPORT_ETHERNET`.
- **Three User-Selectable Bike / Vehicle Speedometer Layouts** under the Aggregate Throughput card (switchable from Engine Settings or via the quick-cycle button):
  - **Superbike 2×2 Cockpit Grid** (260° radial dials with crimson redline zone, tapered spring-damped needle, peak-hold + AVG markers).
  - **Auto Sport Horizontal Dash** (compact left tach + motorsport digital LIVE/MAX/AVG telemetry strip).
  - **Compact Racing Tach Strip** (20-segment LED shift-light bars turning amber/redline at high speed).
- **Per-Network MAX, AVG, Stability %, and Anti-Dip Rescues Telemetry**: Every gauge tracks live speed, session peak (MAX), true active average (AVG), active worker count, interface/capacity, and total bytes pulled.
- **Non-Root Permissions Readiness Card** in Engine Settings with 1-tap request buttons for Notifications, Nearby Wi-Fi Scan (`NEARBY_WIFI_DEVICES` / `ACCESS_FINE_LOCATION` ≤ API 32), and Unrestricted Battery Exemption.
- **1-Tap "Use Kotlin OkHttp & Retry" Fallback Button** on failed tasks when running preview APKs without `libaether_core.so`.
- **Automated GitHub Releases CI/CD Pipeline** with per-build **unique APK package names** (`com.aistudio.aetherio.b<run_number>`), SHA-256 verification, and categorized release notes.

### Upgraded & Enhanced
- **Rust Core (`libaether_core.so` v9.0)**:
  - Persistent pooled `wreq::Client` cache per `(LinkKey, BrowserProfile, Proxy)` to preserve warm TLS 1.3 / HTTP/2 connections across segments and eliminate TCP Slow Start dips.
  - **Decoupled Async Disk-Writer Task** (`mpsc::channel(16)`) with 512 KiB slab coalescing and batched `fdatasync` + `AETHLDG2` ledger commits every 16 MiB / 2s so network sockets never stall waiting on flash I/O.
  - **True Atomic Live Range Bisection** (`ActiveRangeState`) so idle workers slice lagging segments in half in flight without waiting for slow tails.
  - Expanded `BoundedSlabPool` from 8 MiB (16 slabs) to **12 MiB (24 slabs)**, 8 MiB TCP `SO_RCVBUF`, 16–32 MiB BDP segment sizing, and up to **16 concurrent workers**.
  - Upgraded 4 KB `memfd` telemetry page to 4-link quad worker packing + `wifi2_goodput_bps`, `wifi2_bytes_total`, `eth_bytes_total`, and `anti_dip_rescues`.
- **Kotlin OkHttp Turbo Engine v9.0**:
  - Persistent per-link `OkHttpClient` pool with `Dispatcher(maxRequests = 64, maxRequestsPerHost = 32)`, `ConnectionPool(32, 5m)`, and `TunedSocketFactory` (4 MiB `SO_RCVBUF` + `TCP_NODELAY`).
  - Lock-free positional `FileChannel.write(buf, offset)` (`pwrite`) with 448–512 KiB direct slab coalescing and non-blocking periodic flush.
  - Full live atomic range bisection (`ActiveOkHttpRange`) up to 16 workers and HTTP/SOCKS5h Companion Wi-Fi 2 Relay support.

### Fixed
- **Fixed 1/5th Wi-Fi Speed Cap**: Eliminated per-segment HTTP client reconstruction, removed OkHttp's default 5-requests-per-host bottleneck, and enlarged socket receive buffers + BDP segment sizes.
- **Fixed Wi-Fi ↔ Mobile Flip-Flopping & 400 Mbps → 3–4 Mbps Speed Dips**: Removed global `writeMutex` + 500 ms blocking `channel.force(false)` stalls that were falsely tripping the 1.5s circuit breaker for 10s; raised watchdog to 4.5s with non-immediate breaker tripping (3 consecutive errors required) and 6s cooldown.
- **Fixed Premature 5G Lease Teardown**: Removed `releaseCellularLease()` from `AetherDownloadService.onDestroy()` so background/task transitions no longer drop active 5G bonding.
- **Fixed OEM Secondary-Link Validation Lag & USB Detection**: `NetworkMonitor` now tracks `TRANSPORT_USB`, secondary `TRANSPORT_WIFI`, and virtual `wifi2:relay` cleanly without dropping valid leases on HyperOS/MIUI/OneUI/ColorOS.

### Changed
- Default concurrent workers increased from `4` to `8` (slider expanded from `1..8` to `1..16`).
- Default `NetworkRouteMode.AUTO_MULTIPATH` renamed to **Quad-Link Bond**.
- CI builds now dynamically override `applicationId` to `com.aistudio.aetherio.b<run_number>` while preserving `namespace = "com.example"` for JNI compatibility.

### Removed
- Removed global `synchronized(writeMutex)` lock from `KotlinOkHttpTransferRunner` that serialized all parallel sockets onto a single thread.
- Removed premature per-segment `OkHttpClient` / `wreq::Client` teardown and blocking per-segment `fsync` stalls.
