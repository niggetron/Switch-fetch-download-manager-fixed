package com.example.engine

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Exact byte-for-byte projection of Rust `TelemetryPage` (4,096 bytes) in
 * `rust/aether-core/src/storage_and_telemetry.rs` (v9.0 Quad-Link).
 *
 * `workerCountsPacked` layout: `[usb:12 | cell:12 | wifi2:12 | wifi1:12 | total:16]`.
 */
data class MemfdPageSnapshot(
    val totalWritten: Long,
    val totalExpected: Long,
    val goodputBps: Long,
    val stateFlags: Long,
    val wifiGoodputBps: Long,
    val cellGoodputBps: Long,
    val ethGoodputBps: Long,
    val ttfbMs: Long,
    val producerSeq: Long,
    val wifiBytesTotal: Long,
    val cellBytesTotal: Long,
    val workerCountsPacked: Long,
    val blockedReasonCode: Long,
    val contiguousVerifiedOffset: Long,
    val cellularQuotaRemaining: Long,
    val wifi2GoodputBps: Long,
    val consumerSeq: Long,
    val wifi2BytesTotal: Long,
    val ethBytesTotal: Long,
    val antiDipRescues: Long,
) {
    val totalWorkers: Int get() = (workerCountsPacked and 0xFFFFL).toInt()
    val wifiWorkers: Int get() = ((workerCountsPacked ushr 16) and 0x0FFFL).toInt()
    val wifi2Workers: Int get() = ((workerCountsPacked ushr 28) and 0x0FFFL).toInt()
    val cellWorkers: Int get() = ((workerCountsPacked ushr 40) and 0x0FFFL).toInt()
    val ethWorkers: Int get() = ((workerCountsPacked ushr 52) and 0x0FFFL).toInt()

    companion object {
        fun packQuadWorkerCounts(total: Int, wifi1: Int, wifi2: Int, cell: Int, usb: Int): Long =
            ((usb.toLong() and 0x0FFFL) shl 52) or
                ((cell.toLong() and 0x0FFFL) shl 40) or
                ((wifi2.toLong() and 0x0FFFL) shl 28) or
                ((wifi1.toLong() and 0x0FFFL) shl 16) or
                (total.toLong() and 0xFFFFL)
    }
}

/**
 * Zero-JNI Read-Only Mmap Telemetry Reader backed by Rust `memfd_create()` +
 * `ParcelFileDescriptor.adoptFd(fd)` mapped READ_ONLY.
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
            wifiGoodputBps = buf.getLong(0x020),
            cellGoodputBps = buf.getLong(0x028),
            ethGoodputBps = buf.getLong(0x030),
            ttfbMs = buf.getLong(0x038),
            producerSeq = buf.getLong(0x040),
            wifiBytesTotal = buf.getLong(0x048),
            cellBytesTotal = buf.getLong(0x050),
            workerCountsPacked = buf.getLong(0x058),
            blockedReasonCode = buf.getLong(0x060),
            contiguousVerifiedOffset = buf.getLong(0x068),
            cellularQuotaRemaining = buf.getLong(0x070),
            wifi2GoodputBps = buf.getLong(0x078),
            consumerSeq = buf.getLong(0x080),
            wifi2BytesTotal = buf.getLong(0x088),
            ethBytesTotal = buf.getLong(0x090),
            antiDipRescues = buf.getLong(0x098),
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
