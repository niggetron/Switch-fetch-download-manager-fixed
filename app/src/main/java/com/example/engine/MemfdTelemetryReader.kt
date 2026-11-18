package com.example.engine

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class MemfdPageSnapshot(
    val totalWritten: Long,
    val totalExpected: Long,
    val goodputBps: Long,
    val stateFlags: Long,
    val producerSeq: Long,
    val consumerSeq: Long,
)

/**
 * Zero-JNI Read-Only Mmap Telemetry Reader backed by Rust `memfd_create()` +
 * `ParcelFileDescriptor.adoptFd(fd)` mapped READ_ONLY.
 * Polled at 2–4 Hz while UI is STARTED and 0 Hz on ON_STOP.
 */
class MemfdTelemetryReader : AutoCloseable {
    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null
    private var mapped: MappedByteBuffer? = null

    val isMapped: Boolean
        get() = mapped != null

    fun attachIfNativeAvailable(): Boolean {
        if (mapped != null) return true
        val rawFd = AetherNativeBridge.createTelemetryMemfdSafe()
        if (rawFd < 0) return false
        return runCatching {
            val adopted = ParcelFileDescriptor.adoptFd(rawFd)
            val fis = FileInputStream(adopted.fileDescriptor)
            val ch = fis.channel
            val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0L, 4096L).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }
            pfd = adopted
            channel = ch
            mapped = buf
            true
        }.getOrElse {
            close()
            false
        }
    }

    fun readSnapshot(): MemfdPageSnapshot? {
        val buf = mapped ?: return null
        return MemfdPageSnapshot(
            totalWritten = buf.getLong(0x000),
            totalExpected = buf.getLong(0x008),
            goodputBps = buf.getLong(0x010),
            stateFlags = buf.getLong(0x018),
            producerSeq = buf.getLong(0x040),
            consumerSeq = buf.getLong(0x080),
        )
    }

    override fun close() {
        mapped = null
        runCatching { channel?.close() }
        runCatching { pfd?.close() }
        channel = null
        pfd = null
    }
}
