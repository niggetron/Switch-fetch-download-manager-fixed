package com.example.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class ArchitectureDecision(
    val id: String,
    val date: String,
    val title: String,
    val rationale: String,
)

/**
 * 1 Hz structured diagnostic flight-recorder telemetry sample for deep root-cause
 * analysis of speed dips, unused links, socket blocked reasons, and worker scaling.
 */
data class FlightRecorderSample(
    val timestampMs: Long,
    val backendBadge: String,
    val instantBps: Long,
    val wifi1Bps: Long,
    val wifi2Bps: Long,
    val cellBps: Long,
    val usbBps: Long,
    val activeWorkers: Int,
    val wifi1Workers: Int,
    val wifi2Workers: Int,
    val cellWorkers: Int,
    val usbWorkers: Int,
    val ttfbMs: Long,
    val stabilityPct: Int,
    val antiDipRescues: Long,
    val blockedReason: String,
    val thermalStatus: String,
)

data class ExportedDiagnosticBundle(
    val txtFile: File,
    val jsonFile: File,
    val txtUri: Uri,
    val jsonUri: Uri,
    val txtContent: String,
    val jsonContent: String,
)

/**
 * Rotating local diagnostics ring buffer (512 lines) + 1 Hz Flight Recorder (300 samples)
 * with strict secret/URL scrubbing, bundled SIL OFL 1.1 font verification, and Save/Share
 * export to both human-readable TXT and structured JSON trace.
 */
object DiagnosticsCenter {
    private const val MAX_LINES = 512
    private const val MAX_FLIGHT_SAMPLES = 300
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val flightSamples = ArrayDeque<FlightRecorderSample>(MAX_FLIGHT_SAMPLES)

    @Volatile
    private var lastFlightSampleMs = 0L

    val decisionLog: List<ArchitectureDecision> = listOf(
        ArchitectureDecision(
            id = "ADR-001",
            date = "2026-11-18",
            title = "Audited Seccomp-Safe pwrite + fdatasync over io_uring",
            rationale = "Bionic seccomp filters io_uring_setup on Android 16 devices. fallocate() + pwrite_all() + fdatasync() runs across all F2FS/FUSE/SAF FDs without SIGSYS.",
        ),
        ArchitectureDecision(
            id = "ADR-002",
            date = "2026-11-18",
            title = "1 GiB Direct-FD MediaStore Threshold",
            rationale = "Sub-1 GiB transfers stream zero-copy to MediaStore IS_PENDING=1 FDs; >=1 GiB resumable downloads use raw F2FS app storage to avoid FUSE IPC & 7-day IS_PENDING expiry.",
        ),
        ArchitectureDecision(
            id = "ADR-003",
            date = "2026-11-18",
            title = "Dual-Engine Toggleability (Rust libaether_core.so + Kotlin OkHttp)",
            rationale = "Allows live A/B device benchmarking between wreq/quinn Rust zero-JNI memfd and Kotlin OkHttp DirectByteBuffer pipelines.",
        ),
        ArchitectureDecision(
            id = "ADR-004",
            date = "2026-11-18",
            title = "Pre-Drop android_getnetworkblockedreason(sockfd) & No-Proxy Cell Isolation",
            rationale = "Queries NDK socket block reason before closing failed TcpStreams and disables wreq system proxy inheritance on cellular rmnet links.",
        ),
        ArchitectureDecision(
            id = "ADR-005",
            date = "2026-11-18",
            title = "Loopback Aria2 JSON-RPC Lockdown",
            rationale = "Disabled by default; binds strictly to 127.0.0.1:6800 and enforces per-boot 32-byte random hex token authentication.",
        ),
        ArchitectureDecision(
            id = "ADR-006",
            date = "2026-11-20",
            title = "Sharded 64-Stream Multi-Socket Pools & Force-All-Links Dispatch",
            rationale = "Eliminates single-TCP HTTP/2 congestion window bottleneck by sharding independent TCP/TLS pools per link up to 64 streams and pinning dedicated lanes on every active radio (Wi-Fi 1, Wi-Fi 2, 5G/LTE, USB).",
        ),
    )

    fun redactSensitive(input: String): String {
        val tokens = input.split(Regex("\\s+"))
        return tokens.joinToString(" ") { token ->
            val lower = token.lowercase()
            when {
                lower.startsWith("token:") ||
                    lower.startsWith("bearer=") ||
                    lower.startsWith("cookie:") ||
                    lower.startsWith("authorization:") ||
                    lower.startsWith("passphrase=") ||
                    lower.startsWith("password=") -> "[REDACTED]"
                '?' in token -> {
                    val idx = token.indexOf('?')
                    val base = token.substring(0, idx)
                    val query = token.substring(idx).lowercase()
                    if ("sig=" in query ||
                        "signature=" in query ||
                        "token=" in query ||
                        "expires=" in query ||
                        "x-amz-" in query
                    ) {
                        "$base?[REDACTED_SIGNED_PARAMS]"
                    } else {
                        token
                    }
                }
                else -> token
            }
        }
    }

    fun log(tag: String, message: String) {
        val ts = System.currentTimeMillis()
        val entry = "[$ts] [$tag] ${redactSensitive(message)}"
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(entry)
        }
    }

    /**
     * Captures a 1 Hz flight-recorder telemetry sample for speed-dip & multi-network root-cause export.
     */
    fun recordFlightSample(telemetry: TelemetrySnapshot) {
        // C12 audit: gate on a MONOTONIC clock (wall-clock steps could stall the
        // recorder for minutes) and make the check-and-set atomic with the deque
        // append — there are two producers (UI 4 Hz loop + engine/service loop)
        // and the old @Volatile check-then-set let both record near-duplicate samples.
        val gateNow = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (gateNow - lastFlightSampleMs < 950L) return
            lastFlightSampleMs = gateNow
            val sample = FlightRecorderSample(
                timestampMs = System.currentTimeMillis(), // display only — not used for intervals
                backendBadge = telemetry.activeEngineBadge,
                instantBps = telemetry.instantBps,
                wifi1Bps = telemetry.wifiBps,
                wifi2Bps = telemetry.wifi2Bps,
                cellBps = telemetry.cellBps,
                usbBps = telemetry.ethBps,
                activeWorkers = telemetry.activeWorkers,
                wifi1Workers = telemetry.wifiWorkers,
                wifi2Workers = telemetry.wifi2Workers,
                cellWorkers = telemetry.cellularWorkers,
                usbWorkers = telemetry.ethWorkers,
                ttfbMs = telemetry.lastTtfbMs,
                stabilityPct = telemetry.stabilityPercent,
                antiDipRescues = telemetry.antiDipRescues,
                blockedReason = telemetry.blockedReasonLabel,
                thermalStatus = telemetry.thermalStatusLabel,
            )
            if (flightSamples.size >= MAX_FLIGHT_SAMPLES) flightSamples.removeFirst()
            flightSamples.addLast(sample)
        }
    }

    fun flightSampleCount(): Int = synchronized(lock) { flightSamples.size }

    fun snapshotFlightSamples(): List<FlightRecorderSample> = synchronized(lock) { flightSamples.toList() }

    fun exportReport(
        context: Context,
        backendLabel: String,
        telemetry: TelemetrySnapshot,
        networkState: NetworkMatrixState? = null,
        shizukuState: ShizukuWifiState? = null,
    ): String {
        val fontAudit = verifyBundledFontLicenses(context)
        val rustLogs = AetherNativeBridge.diagnosticsLogSafe()
        val kotlinLogs = synchronized(lock) { lines.joinToString("\n") }
        val samples = snapshotFlightSamples()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        return buildString {
            appendLine("=== AetherIO Omega v9.1 Hyperion — Flight Recorder TXT Report ===")
            appendLine("Generated: ${fmt.format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Backend: $backendLabel | Rust Native Loaded: ${AetherNativeBridge.isNativeLoaded} | Memfd: ${telemetry.isMemfdMapped}")
            if (!AetherNativeBridge.isNativeLoaded) {
                appendLine("Rust Load Note: ${AetherNativeBridge.loadError ?: "libaether_core.so compiled via GitHub Actions arm64-v8a"}")
            }
            appendLine("Thermal: ${telemetry.thermalStatusLabel} | Blocked Reason: ${telemetry.blockedReasonLabel}")
            appendLine("Goodput NOW: ${formatRate(telemetry.instantBps)} | MAX: ${formatRate(telemetry.peakBps)} | AVG: ${formatRate(telemetry.avgBps)} | Stability: ${telemetry.stabilityPercent}%")
            appendLine("Per-Link NOW: Wi-Fi1=${formatRate(telemetry.wifiBps)} (w=${telemetry.wifiWorkers}), Wi-Fi2=${formatRate(telemetry.wifi2Bps)} (w=${telemetry.wifi2Workers}), 5G/Cell=${formatRate(telemetry.cellBps)} (w=${telemetry.cellularWorkers}), USB=${formatRate(telemetry.ethBps)} (w=${telemetry.ethWorkers})")
            appendLine("Per-Link MAX: Wi-Fi1=${formatRate(telemetry.wifiPeakBps)}, Wi-Fi2=${formatRate(telemetry.wifi2PeakBps)}, 5G/Cell=${formatRate(telemetry.cellPeakBps)}, USB=${formatRate(telemetry.ethPeakBps)}")
            appendLine("Per-Link Bytes: Wi-Fi1=${telemetry.wifiBytesTotal}, Wi-Fi2=${telemetry.wifi2BytesTotal}, 5G/Cell=${telemetry.cellBytesTotal}, USB=${telemetry.ethBytesTotal}")
            appendLine("Anti-Dip Rescues: ${telemetry.antiDipRescues} | Last TTFB: ${telemetry.lastTtfbMs} ms | Slab: ${"%.1f".format(telemetry.slabInUseMb)}/${"%.1f".format(telemetry.slabAllocatedMb)} MB")
            if (networkState != null) {
                appendLine("--- Network Interface Matrix ---")
                appendLine("Active Validated Links: ${networkState.activeLinkCount} | Cellular Lease: ${networkState.cellularLeaseRequested} | DataSaver: ${networkState.dataSaverLabel}")
                appendLine("Dual-STA HW: multi=${networkState.dualStaHardwareCapable}, local=${networkState.dualStaLocalOnlyCapable}, 5GHz=${networkState.supports5Ghz}, 6GHz=${networkState.supports6Ghz}")
                listOfNotNull(networkState.wifiLink, networkState.wifi2Link, networkState.cellularLink, networkState.ethernetLink)
                    .forEach { link ->
                        appendLine("  * ${link.linkKey.id} | iface=${link.interfaceName} | ip=${link.localIp} | handle=${link.netHandle} | band=${link.bandLabel} | rssi=${link.rssiDbm ?: "n/a"} | breaker=${link.breakerState} | ewma=${"%.1f".format(link.ewmaMbps)} Mbps | relay=${link.isRelay}")
                    }
            }
            if (shizukuState != null) {
                appendLine("Shizuku: ${shizukuState.status.label} (${shizukuState.versionLabel}) | LowLatency=${shizukuState.lowLatencyModeActive}")
            }
            appendLine("Fonts Audit: $fontAudit")
            appendLine("--- Active Socket Connections (${telemetry.activeConnections.size}) ---")
            if (telemetry.activeConnections.isEmpty()) {
                appendLine("(none active at capture moment)")
            } else {
                telemetry.activeConnections.take(64).forEach { c ->
                    appendLine("  [${c.connId}] link=${c.linkKeyId} proto=${c.protocol} state=${c.socketState} speed=${formatRate(c.speedBps)} ttfb=${c.ttfbMs}ms bytes=${c.bytesReceived} range=${c.rangeStart}-${c.rangeEnd}")
                }
            }
            appendLine("--- 1 Hz Flight Recorder Time-Series (${samples.size} samples) ---")
            samples.takeLast(60).forEach { s ->
                appendLine("  ${fmt.format(Date(s.timestampMs))} | agg=${formatRate(s.instantBps)} w1=${formatRate(s.wifi1Bps)}(${s.wifi1Workers}) w2=${formatRate(s.wifi2Bps)}(${s.wifi2Workers}) cell=${formatRate(s.cellBps)}(${s.cellWorkers}) usb=${formatRate(s.usbBps)}(${s.usbWorkers}) rtt=${s.ttfbMs}ms stab=${s.stabilityPct}% rescues=${s.antiDipRescues} blk=${s.blockedReason}")
            }
            appendLine("--- Native Rust Ring ---")
            appendLine(rustLogs.ifBlank { "(native library inactive or ring empty)" })
            appendLine("--- Kotlin Coordinator Ring ---")
            appendLine(kotlinLogs.ifBlank { "(no Kotlin events recorded yet)" })
        }
    }

    fun exportStructuredJsonTrace(
        backendLabel: String,
        telemetry: TelemetrySnapshot,
        networkState: NetworkMatrixState? = null,
        shizukuState: ShizukuWifiState? = null,
    ): String {
        val samples = snapshotFlightSamples()
        val kotlinLogs = synchronized(lock) { lines.toList() }
        val rustLogs = AetherNativeBridge.diagnosticsLogSafe().lines().filter { it.isNotBlank() }

        fun esc(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

        return buildString {
            appendLine("{")
            appendLine("  \"schema\": \"aetherio_flight_trace_v9_1\",")
            appendLine("  \"capturedAtMs\": ${System.currentTimeMillis()},")
            appendLine("  \"device\": {")
            appendLine("    \"manufacturer\": \"${esc(Build.MANUFACTURER)}\",")
            appendLine("    \"model\": \"${esc(Build.MODEL)}\",")
            appendLine("    \"sdkInt\": ${Build.VERSION.SDK_INT},")
            appendLine("    \"abis\": [${Build.SUPPORTED_ABIS.joinToString(",") { "\"${esc(it)}\"" }}]")
            appendLine("  },")
            appendLine("  \"engine\": {")
            appendLine("    \"backend\": \"${esc(backendLabel)}\",")
            appendLine("    \"rustLoaded\": ${AetherNativeBridge.isNativeLoaded},")
            appendLine("    \"memfdMapped\": ${telemetry.isMemfdMapped},")
            appendLine("    \"instantBps\": ${telemetry.instantBps},")
            appendLine("    \"peakBps\": ${telemetry.peakBps},")
            appendLine("    \"avgBps\": ${telemetry.avgBps},")
            appendLine("    \"stabilityPercent\": ${telemetry.stabilityPercent},")
            appendLine("    \"antiDipRescues\": ${telemetry.antiDipRescues},")
            appendLine("    \"blockedReason\": \"${esc(telemetry.blockedReasonLabel)}\",")
            appendLine("    \"thermalStatus\": \"${esc(telemetry.thermalStatusLabel)}\",")
            appendLine("    \"shizukuStatus\": \"${esc(shizukuState?.status?.name ?: "UNKNOWN")}\"")
            appendLine("  },")
            appendLine("  \"links\": [")
            val activeLinks = if (networkState != null) {
                listOfNotNull(networkState.wifiLink, networkState.wifi2Link, networkState.cellularLink, networkState.ethernetLink)
            } else emptyList()
            activeLinks.forEachIndexed { idx, link ->
                val comma = if (idx < activeLinks.lastIndex) "," else ""
                appendLine("    {\"id\":\"${esc(link.linkKey.id)}\",\"iface\":\"${esc(link.interfaceName)}\",\"ip\":\"${esc(link.localIp)}\",\"netHandle\":${link.netHandle},\"band\":\"${esc(link.bandLabel)}\",\"rssiDbm\":${link.rssiDbm ?: 0},\"ewmaMbps\":${"%.2f".format(Locale.US, link.ewmaMbps)},\"breaker\":\"${link.breakerState.name}\",\"isRelay\":${link.isRelay}}$comma")
            }
            appendLine("  ],")
            appendLine("  \"samples\": [")
            samples.forEachIndexed { idx, s ->
                val comma = if (idx < samples.lastIndex) "," else ""
                appendLine("    {\"ts\":${s.timestampMs},\"aggBps\":${s.instantBps},\"w1Bps\":${s.wifi1Bps},\"w2Bps\":${s.wifi2Bps},\"cellBps\":${s.cellBps},\"usbBps\":${s.usbBps},\"wTotal\":${s.activeWorkers},\"w1\":${s.wifi1Workers},\"w2\":${s.wifi2Workers},\"wCell\":${s.cellWorkers},\"wUsb\":${s.usbWorkers},\"ttfbMs\":${s.ttfbMs},\"stab\":${s.stabilityPct},\"rescues\":${s.antiDipRescues},\"blk\":\"${esc(s.blockedReason)}\"}$comma")
            }
            appendLine("  ],")
            appendLine("  \"rustLogs\": [${rustLogs.takeLast(120).joinToString(",") { "\"${esc(redactSensitive(it))}\"" }}],")
            appendLine("  \"kotlinLogs\": [${kotlinLogs.takeLast(120).joinToString(",") { "\"${esc(it)}\"" }}]")
            appendLine("}")
        }
    }

    /**
     * Writes both TXT and JSON diagnostic files into app cache and exposes them via FileProvider
     * so users can Share them directly or inspect them cleanly.
     */
    fun writeShareableBundle(
        context: Context,
        backendLabel: String,
        telemetry: TelemetrySnapshot,
        networkState: NetworkMatrixState? = null,
        shizukuState: ShizukuWifiState? = null,
    ): ExportedDiagnosticBundle {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val txtContent = exportReport(context, backendLabel, telemetry, networkState, shizukuState)
        val jsonContent = exportStructuredJsonTrace(backendLabel, telemetry, networkState, shizukuState)
        val txtFile = File(dir, "aetherio_diag_$stamp.txt").apply { writeText(txtContent) }
        val jsonFile = File(dir, "aetherio_trace_$stamp.json").apply { writeText(jsonContent) }
        val authority = "${context.packageName}.fileprovider"
        val txtUri = FileProvider.getUriForFile(context, authority, txtFile)
        val jsonUri = FileProvider.getUriForFile(context, authority, jsonFile)
        return ExportedDiagnosticBundle(
            txtFile = txtFile,
            jsonFile = jsonFile,
            txtUri = txtUri,
            jsonUri = jsonUri,
            txtContent = txtContent,
            jsonContent = jsonContent,
        )
    }

    private fun formatRate(bps: Long): String = when {
        bps >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB/s", bps / (1024.0 * 1024.0))
        bps >= 1024L -> String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
        else -> "$bps B/s"
    }

    fun verifyBundledFontLicenses(context: Context): String {
        val assetOk = runCatching {
            context.assets.open("font_licenses.txt").use { stream ->
                val txt = stream.bufferedReader().readText()
                txt.contains("SIL Open Font License", ignoreCase = true) &&
                    txt.contains("Space Grotesk") &&
                    txt.contains("Inter") &&
                    txt.contains("JetBrains Mono")
            }
        }.getOrDefault(false)

        val fontsPresent = listOf("space_grotesk", "inter", "jetbrains_mono").count { name ->
            context.resources.getIdentifier(name, "font", context.packageName) != 0
        }
        return if (assetOk && fontsPresent == 3) {
            "SIL OFL 1.1 Verified (3/3 local .ttf)"
        } else {
            "Font manifest check ($fontsPresent/3 fonts, license=$assetOk)"
        }
    }
}
