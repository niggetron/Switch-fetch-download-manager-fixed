package com.example.engine

/**
 * Tier-2 JNI Bridge to `libaether_core.so` (Rust `aether-core` crate in `/rust/aether-core/`).
 *
 * Checks at startup whether `libaether_core.so` is packaged in `jniLibs`.
 * When running in environments without compiled native Rust binaries, falls back
 * transparently to the Kotlin DirectByteBuffer + FileChannel engine.
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
            "Kotlin DirectByteBuffer + FileChannel (libaether_core.so not bundled)"
        }

    external fun nativeEngineVersion(): String
    external fun nativeArenaUsedBytes(): Long
    external fun nativeF2fsExtentBytes(): Int
    external fun nativeRecordTelemetryCommit(bytes: Long): Long
    external fun nativeBlake3Hex(data: ByteArray): String
}
