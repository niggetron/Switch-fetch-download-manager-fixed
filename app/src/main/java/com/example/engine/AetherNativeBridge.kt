package com.example.engine

/**
 * Tier-2 JNI bridge to `libaether_core.so` (stable-Rust `aether-core` crate in `/rust/aether-core/`).
 *
 * The CI workflow cross-compiles the crate for arm64-v8a and drops the result into
 * `app/src/main/jniLibs/arm64-v8a/` before the Gradle build. When the library is
 * absent (local dev builds without the Rust toolchain), every public helper here
 * degrades to a no-op so the Kotlin OkHttp engine keeps working.
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
                .getOrDefault("libaether_core.so (Rust JNI Active)")
        } else {
            "Kotlin OkHttp (libaether_core.so not bundled - CI builds arm64-v8a)"
        }

    /** Native BLAKE3 hex digest, or null when the native core is unavailable. */
    fun blake3Hex(data: ByteArray): String? =
        if (isNativeLoaded) runCatching { nativeBlake3Hex(data) }.getOrNull() else null

    fun verifyBlake3(data: ByteArray, expectedHex: String): Boolean =
        isNativeLoaded && runCatching { nativeVerifyBlake3(data, expectedHex) }.getOrDefault(0) != 0

    /** GF(2^8) XOR-accumulate used by the RLNC repair path. */
    fun gf256MulAcc(dst: ByteArray, src: ByteArray, coeff: Int): Boolean =
        isNativeLoaded && runCatching { nativeGf256MulAcc(dst, src, coeff) }.getOrDefault(-1) == 0

    /** 0 = Wi-Fi, 1 = cellular, -1 = no usable link, null = native core unavailable. */
    fun selectLink(
        wifiRate: Double,
        wifiQueue: Double,
        wifiRtt: Double,
        cellRate: Double,
        cellQueue: Double,
        cellRtt: Double,
        blockBytes: Double,
    ): Int? = if (isNativeLoaded) {
        runCatching {
            nativeSelectLink(
                wifiRate, wifiQueue, wifiRtt,
                cellRate, cellQueue, cellRtt,
                blockBytes,
            )
        }.getOrNull()
    } else null

    fun f2fsExtentCount(totalBytes: Long): Int? =
        if (isNativeLoaded) runCatching { nativeF2fsExtentCount(totalBytes) }.getOrNull() else null

    /** Record a committed byte count in the native telemetry page; returns the running total. */
    fun recordCommit(bytes: Long): Long =
        if (isNativeLoaded) runCatching { nativeRecordTelemetryCommit(bytes) }.getOrDefault(0L) else 0L

    fun nativeTotalBytes(): Long = recordCommit(0L)

    fun arenaUsedBytes(): Long =
        if (isNativeLoaded) runCatching { nativeArenaUsedBytes() }.getOrDefault(0L) else 0L

    fun f2fsExtentBytes(): Int =
        if (isNativeLoaded) runCatching { nativeF2fsExtentBytes() }.getOrDefault(0) else 0

    external fun nativeEngineVersion(): String
    external fun nativeArenaUsedBytes(): Long
    external fun nativeF2fsExtentBytes(): Int
    external fun nativeRecordTelemetryCommit(bytes: Long): Long
    external fun nativeBlake3Hex(data: ByteArray): String
    external fun nativeVerifyBlake3(data: ByteArray, expectedHex: String): Int
    external fun nativeGf256MulAcc(dst: ByteArray, src: ByteArray, coeff: Int): Int
    external fun nativeSelectLink(
        wifiRate: Double,
        wifiQueue: Double,
        wifiRtt: Double,
        cellRate: Double,
        cellQueue: Double,
        cellRtt: Double,
        blockBytes: Double,
    ): Int
    external fun nativeF2fsExtentCount(totalBytes: Long): Int
}
