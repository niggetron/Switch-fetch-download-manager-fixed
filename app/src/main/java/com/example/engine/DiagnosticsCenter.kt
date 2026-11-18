package com.example.engine

import android.content.Context
import android.os.Build
import java.util.ArrayDeque

data class ArchitectureDecision(
    val id: String,
    val date: String,
    val title: String,
    val rationale: String,
)

/**
 * Rotating local diagnostics ring buffer (256 lines) with strict secret/URL scrubbing,
 * bundled SIL OFL 1.1 font verification, and immutable dated Architecture Decision Log.
 */
object DiagnosticsCenter {
    private const val MAX_LINES = 256
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES)

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
    )

    fun redactSensitive(input: String): String {
        val tokens = input.split(Regex("\\s+"))
        return tokens.joinToString(" ") { token ->
            val lower = token.lowercase()
            when {
                lower.startsWith("token:") ||
                    lower.startsWith("bearer=") ||
                    lower.startsWith("cookie:") ||
                    lower.startsWith("authorization:") -> "[REDACTED]"
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

    fun exportReport(
        context: Context,
        backendLabel: String,
        telemetry: TelemetrySnapshot,
    ): String {
        val fontAudit = verifyBundledFontLicenses(context)
        val rustLogs = AetherNativeBridge.diagnosticsLogSafe()
        val kotlinLogs = synchronized(lock) { lines.joinToString("\n") }
        return buildString {
            appendLine("=== AetherIO v8.0 Diagnostics Report ===")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Backend: $backendLabel")
            appendLine("Memfd Mapped: ${telemetry.isMemfdMapped} | Thermal: ${telemetry.thermalStatusLabel}")
            appendLine("Aggregate Goodput: ${telemetry.instantBps} B/s (Wi-Fi=${telemetry.wifiBps} B/s, 5G=${telemetry.cellBps} B/s, Eth=${telemetry.ethBps} B/s)")
            appendLine("Fonts Audit: $fontAudit")
            appendLine("--- Native Rust Ring ---")
            appendLine(rustLogs.ifBlank { "(native library inactive or ring empty)" })
            appendLine("--- Kotlin Coordinator Ring ---")
            appendLine(kotlinLogs.ifBlank { "(no Kotlin events recorded yet)" })
        }
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
