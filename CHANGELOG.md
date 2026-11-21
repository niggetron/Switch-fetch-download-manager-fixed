# Changelog

All notable changes to **AetherIO** are documented in this file. GitHub Releases are generated from the actual commits between tags (`Added`/`Fixed`/`Upgraded & Enhanced`/`Changed`/`Removed`) plus the comparison link — this file is the human-readable history.

## [9.2.3] - OkHttp Engine Hot-Path Optimisation

### Upgraded & Enhanced
- OkHttp engine now accumulates per-chunk byte accounting into a single per-slab update (was 4-6 contended atomics per 8-64 KiB socket read).
- Replaced the O(segments) pending scan under the global claim mutex with an O(1) atomic counter.
- Telemetry connection upserts throttled from 150 ms to 450 ms; at 128 workers this removes ~640 `ActiveConnectionInfo` allocations/sec.
- Worker pool now launches enough coroutines for the governor's full ramp range, so a target of 96-128 actually materializes instead of being a no-op.
- OkHttp built-in retries disabled (we own backoff); timeouts raised to 30 s; `callTimeout=0`.
- Stale per-network-handle OkHttp clients are evicted on rotation to prevent socket/thread/FD leaks.
- Flush threshold aligned with the actual 256 KiB slab.

### Fixed
- Bisection failure now cancels sibling ranges, restores the full segment and resets to PENDING — no overlapping writes and no permanently un-COMMITTABLE segments.
- `Retry-After` backoff no longer trips the stall watchdog and false-breaks a compliant server.
- `LinkStallException` classified as a network flap so migration logic runs.

## [9.2.2] - 128 Lanes, Engine Correctness and Honest Telemetry

### Added
- Per-host connection-limit learning (two refusals, 10-minute decay) adapted from Mister Download Manager, so a server that allows 2 connections gets 2 instead of 128 wasted sockets.
- Native `resumeTask` JNI API; aria2-next driven with the correct string GID result and 128-connection options.
- Share-sheet intake: shared `http(s)` links are queued from `ACTION_SEND`.

### Upgraded & Enhanced
- Connection ceilings raised to 128 across Rust, OkHttp and aria2 with thermal/host-aware governors; small files start with one 64 KiB piece per connection.
- Rust telemetry atomics are published once per 120 ms instead of once per network chunk; CAS scratch buffer allocated only when a real out-of-order gap exists.
- `Retry-After` on 429/503 honored as a floor (capped at 10 minutes).
- `PARTIAL_WAKE_LOCK` held by the FGS and WorkManager fallback.

### Fixed
- Range-ignoring servers returning HTTP 200 are rejected in both engines instead of corrupting the file.
- Rust no longer truncates unknown-length responses to the 16 KiB probe and falsely reports COMPLETED.
- Anti-dip split segments commit only after both halves are on disk.
- Open/Share works again (FileProvider `Download/` path).
- Backup rules no longer expose signed URLs, SAF URIs and proxy settings.
- Speed dashboard no longer presents fabricated upload/latency/jitter/packet-loss/quality figures or fake map pins.

## [9.2.1] - Removing the Hidden 16-Lane Ceiling

### Upgraded & Enhanced
- Android 16 CPU-headroom values are no longer misread as percentages and used to hard-cap the native engine at 16 workers. PowerManager thermal status now maps to a soft 24/32/40/48 lane ceiling.
- Network range size reduced to 256 KiB–4 MiB so a typical file exposes enough claimable ranges for 32–48 sockets (Ghost Downloader / aria2-next strategy).
- Native slab pool raised to 64 × 256 KiB (16 MiB) so the semaphore no longer limits concurrency below the worker cap.
- Default worker count raised to 32; Rust runtime and aria2-next daemon are warmed during app startup so the first download starts immediately.
- aria2-next tuned with 16 connections/server, 1 MiB splits, no file allocation, 64 MiB disk cache, HTTP/2, retry/backoff and concurrent-download optimization.
- Geo lookup now tries ipapi.co, ip-api.com and ipinfo.io with caching; dashboard shows the live download host, resolved CDN IP, city/country and ISP even when one provider fails.

### Fixed
- Task status flips to STREAMING immediately on dispatch instead of appearing queued while native/aria2 warms up.

## [9.2.0] - Triple-Engine Fork: aria2-next, Live Speed Dashboard & Adaptive Throughput

### Added
- **aria2-next (Rayburst) engine**: CI bundles the SHA-256-verified arm64 executable as an APK asset; the app runs it as a loopback JSON-RPC daemon with a per-boot secret and exposes `aria2.addUri`/`tellStatus`/`pause`/`remove`. Selectable in Engine settings and in the per-download backend chips.
- **Cloudflare-style Speed dashboard tab**: Animated download/upload area charts, Latency/Jitter/Packet-Loss tiles, Network Quality Score, geo-located world map with live route (CDN edge IP → your public IP via ipapi.co), and latency/download/upload distribution cards.
- **Shizuku privilege prompt** on the dashboard when Shizuku is installed but not authorized, with a direct grant/start action.
- Engine binary/privilege status card with actionable aria2-next diagnostics.
- Explicit `noCompress` asset rule for the aria2-next ELF and ELF-magic verification after extraction.

### Upgraded & Enhanced
- Adaptive lane governors in **both** Rust and Kotlin now use EWMA smoothing and can scale up to 48 lanes when the link is healthy; they shed lanes gently on sustained goodput loss instead of collapsing.
- Native links are re-pushed to the Rust core before every download and on network changes, preventing `default:auto` unbound workers during Wi-Fi/cellular reconnects.
- Wi-Fi links accepted with INTERNET + usable IP even when OEM ROMs omit `NET_CAPABILITY_VALIDATED`.
- Shared-element cockpit spring transitions for dashboard charts and the server map.

### Fixed
- **Startup SIGSYS crash**: removed the seccomp-blocked raw `io_uring_setup` syscall (arm64 425) from `probe_io_uring_supported()`.
- **`libc++_shared.so` missing from APK**: CI now bundles the NDK C++ runtime; `dlopen` no longer fails.
- Release notes no longer paste the same historical changelog section into every release.
- Misleading "aria2-next binary not bundled" error now reports the real extraction/daemon failure.
- Rust unit-test expectations updated for the adaptive worker caps.

### Changed
- Worker caps: Rust 48, Kotlin 40/48/32 by throughput profile (was a hard 64).
- Retry backoff is exponential (was a fixed 120 ms hammer loop).
- Stale (> 5 s) TTFB samples are ignored by both governors.

### Removed
- Nightly-only Rust APIs (`allocator_api`, `DropGuard`, `conjure_zst`, `as_mut_unchecked`) — core now builds fully on stable Rust in CI.

## [9.1] - Hyperion 64-Stream Force-All-Links Engine, Rayburst UI, 1DM+ Properties, Shizuku Wi-Fi 2 Picker & 18-Theme Color Studio

### Added
- **Rayburst-Style Download Cards & 1:1 Simulator Parity**: Striped 64-lane multi-socket progress bars, 64-cell piece matrix, animated live segment pills, and full per-file telemetry (**LIVE, MAX, AVG, Active/Max Connections, Remaining, Active Time, Started/Ended timestamps, and ETA**) on both live transfer cards and the simulated Rayburst card in Simulation Mode.
- **1DM+ Super-Properties Inspector (`DownloadPropertiesSheet`)**: Tapping any real or simulated transfer/file opens a detailed properties modal showing Name, URL, Save Path, Wi-Fi Only, Resume/Range support, exact byte sizes, LIVE/MAX/AVG speeds, Active/Max Connections, Date Added, Active Time, Date Modified/Ended, Engine Backend, Per-Link Byte Breakdown, async `MediaMetadataRetriever` stream info (Bitrate, Duration, Resolution, Video/Audio codecs), and on-demand **MD5 & SHA-256 CALCULATE** buttons with shimmer loading.
- **Telegram-Style Color Studio with 18 High-Contrast Themes & App-Wide Neon Glow**: Live interactive mini phone mockup, 18 WCAG AA-verified themes (Obsidian Cyan, Emerald Matrix, Crimson Nitro, Solar Amber, Royal Violet, Vaporwave Neon, Arctic Daylight, Pure OLED Stealth, Dracula Velvet, Nord Frost, Catppuccin Mocha, Gruvbox Ember, Tokyo Night, Solarized Dusk, Sakura Blossom, Cyber Lime, Deep Ocean, Monograph Crisp), 8 per-element color pickers, live contrast score meter, and global **Neon Glow** toggle + intensity slider.
- **3 New Speedometer / Progress Styles (6 Total)**: Added **F1 Telemetry HUD**, **Neon Plasma Arc**, and **Rayburst Matrix Bar** alongside Superbike Grid, Auto Sport Dash, and Compact Tach Strip.
- **In-App Auto Wi-Fi 2 Picker + Shizuku Privilege Helper (`ShizukuWifiHelper`)**: Auto-fetches connected Wi-Fi 1 SSID + nearby Wi-Fi networks with signal strength, band tags, inline password prompt, Shizuku unthrottled scan + low-latency Wi-Fi boost, Single-STA rollback, and Relay/USB fallback.
- **Skeleton UI & Luminance-Adaptive Shimmer Animations**: Added across Wi-Fi scanning, Stream 0 header probing, Remote `.zip` EOCD probing, and on-demand media/hash inspection.
- **Deep Diagnostic Flight Recorder**: Records 1 Hz snapshots (goodput, per-link speeds, active workers, TTFB, stalls, socket states, network matrix, Shizuku state) and exports redacted **TXT** + structured **JSON** traces via Save to Storage (SAF) or Share Bundle (`FileProvider`).
- **Remote `.zip` Selective Extraction (EOCD 64 KB Range Probe)** & **HLS/DASH Parallel Multi-Segment Downloader**.

### Upgraded & Enhanced
- **64-Stream Sharded Multi-Socket Pools & Force-All-Links (Rust + Kotlin OkHttp)**: Sharded independent TCP sockets (`wreq::Client` & `OkHttpClient` shards) eliminate HTTP/2 single-connection window caps, Ghost-style closed-loop `autoSpeedUp` ramps smoothly up to 64 streams, and **Force-All-Links** guarantees active stream reservations on Mobile 5G/LTE and Wi-Fi 2 so they are never starved by Wi-Fi 1.
- **8-Color Distinct Architecture Audit Radar**: Each of the 8 axes now renders with its own high-contrast color and interactive legend.
- **16 KB ELF Alignment CI Verification**: GitHub Actions verifies `0x4000` LOAD alignment on `libaether_core.so` (`arm64-v8a`) for Android 15/16 compliance.

### Fixed
- **Fixed Blank Gauge Zoom**: Removed nested `SharedTransitionLayout` scopes and fixed `FocusedGaugeOverlay` so tapping any speedometer zooms smoothly without blanking or freezing.
- **Fixed Shared-Element Jitter & Text Overlap**: Simplified shared-element morph keys to container bounds to eliminate glitching and overlapping labels.
- **Fixed Dark-on-Dark Low-Contrast Text**: Added automatic WCAG luminance-based `contrastOnColor()` across chips, buttons, and all 18 Color Studio themes.

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
