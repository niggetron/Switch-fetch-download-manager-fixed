package com.example.engine

/**
 * Tier-2 JNI Control Bridge to `libaether_core.so` (Rust `aether-core` v7.1).
 *
 * When `libaether_core.so` is packaged in `jniLibs/arm64-v8a/` (via `cargo-ndk` in CI or locally),
 * exposes BoundedSlabPool (8.0 MiB), memfd_create() telemetry page, per-LinkKey handle updates,
 * staged fd stream-copy, and BLAKE3/SHA-256 verification.
 *
 * When running in Kotlin-only environments without `.so`, falls back gracefully to the
 * Kotlin DirectByteBuffer + FileChannel engine and reports the active backend transparently.
 */
object AetherNativeBridge {
    val isNativeLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("aether_core")
            true
        }.getOrDefault(false)
    }

    val engineBackendLabel: String
        get() = if (isNativeLoaded) {
            runCatching { nativeEngineVersion() }
                .getOrDefault("libaether_core.so v7.1 (Rust JNI Active)")
        } else {
            "Kotlin DirectByteBuffer + FileChannel (libaether_core.so not bundled)"
        }

    fun updateLinkHandleSafe(
        linkKey: String,
        netHandle: Long,
        downstreamKbps: Long,
        rttMs: Long,
        isCellular: Boolean,
        proxyHost: String,
        proxyPort: Int,
    ) {
        if (!isNativeLoaded) return
        runCatching {
            nativeUpdateLinkHandle(
                linkKey = linkKey,
                netHandle = netHandle,
                downstreamKbps = downstreamKbps,
                rttMs = rttMs,
                isCellular = isCellular,
                proxyHost = proxyHost,
                proxyPort = proxyPort,
            )
        }
    }

    fun removeLinkSafe(linkKey: String) {
        if (!isNativeLoaded) return
        runCatching { nativeRemoveLink(linkKey) }
    }

    fun pauseQuotaGuardSafe() {
        if (!isNativeLoaded) return
        runCatching { nativePauseQuotaGuard() }
    }

    fun slabStatsSafe(): LongArray? {
        if (!isNativeLoaded) return null
        return runCatching { nativeSlabStats() }.getOrNull()
    }

    fun copyStagingToFdSafe(stagingPath: String, destFd: Int): Boolean {
        if (!isNativeLoaded) return false
        return runCatching { nativeCopyStagingToFd(stagingPath, destFd) }.getOrDefault(false)
    }

    fun createTelemetryMemfdSafe(): Int {
        if (!isNativeLoaded) return -1
        return runCatching { nativeCreateTelemetryMemfd() }.getOrDefault(-1)
    }

    external fun nativeEngineVersion(): String
    external fun nativeCreateTelemetryMemfd(): Int
    external fun nativeUpdateLinkHandle(
        linkKey: String,
        netHandle: Long,
        downstreamKbps: Long,
        rttMs: Long,
        isCellular: Boolean,
        proxyHost: String,
        proxyPort: Int,
    )
    external fun nativeRemoveLink(linkKey: String)
    external fun nativeSlabStats(): LongArray
    external fun nativeCopyStagingToFd(stagingPath: String, destFd: Int): Boolean
    external fun nativePauseQuotaGuard()
    external fun nativeBlake3Hex(data: ByteArray): String
    external fun nativeSha256Hex(data: ByteArray): String
}
