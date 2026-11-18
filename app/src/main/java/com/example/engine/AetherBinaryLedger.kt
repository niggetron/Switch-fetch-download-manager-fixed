package com.example.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Crash-safe AETHLDG1 binary ledger (v7.1 Table 7.1) byte-for-byte compatible
 * with `rust/aether-core/src/ledger.rs`.
 */
class AetherBinaryLedger(
    val totalLen: Long,
    val segmentBytes: Long,
    val validatorHash: ByteArray,
    val states: ByteArray,
) {
    fun encode(): ByteArray {
        val bodyLen = HEADER_LEN + states.size
        val buf = ByteBuffer.allocate(bodyLen + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putInt(VERSION)
        buf.putInt(0)
        buf.putLong(totalLen)
        buf.putLong(segmentBytes)
        buf.putInt(states.size)
        buf.putInt(0)
        buf.put(validatorHash.copyOf(8))
        buf.put(ByteArray(16))
        buf.put(states)
        val raw = buf.array()
        val crc = computeCrc32c(raw, 0, bodyLen)
        buf.putInt(bodyLen, crc)
        return raw
    }

    @Synchronized
    fun persist(file: File) {
        file.parentFile?.mkdirs()
        val data = encode()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(data.size.toLong())
            raf.seek(0L)
            raf.write(data)
            raf.fd.sync()
        }
    }

    companion object {
        val MAGIC = "AETHLDG1".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        const val HEADER_LEN = 64

        const val STATE_PENDING: Byte = 0
        const val STATE_LEASED: Byte = 1
        const val STATE_DONE: Byte = 2

        private val CRC32C_TABLE = IntArray(256) { i ->
            var c = i
            repeat(8) {
                c = if ((c and 1) != 0) (c ushr 1) xor 0x82F63B78.toInt() else c ushr 1
            }
            c
        }

        fun computeCrc32c(data: ByteArray, offset: Int, length: Int): Int {
            var crc = -1
            for (i in offset until (offset + length)) {
                val idx = (crc xor (data[i].toInt() and 0xFF)) and 0xFF
                crc = (crc ushr 8) xor CRC32C_TABLE[idx]
            }
            return crc.inv()
        }

        fun validatorHash(etagOrLastMod: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(etagOrLastMod.toByteArray(Charsets.UTF_8))
            return digest.copyOf(8)
        }

        fun decode(raw: ByteArray): AetherBinaryLedger? {
            if (raw.size < HEADER_LEN + 4) return null
            if (!raw.copyOfRange(0, 8).contentEquals(MAGIC)) return null
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf.getInt(8)
            if (version != VERSION) return null
            val totalLen = buf.getLong(16)
            val segBytes = buf.getLong(24)
            val segCount = buf.getInt(32)
            if (segCount < 0 || raw.size != HEADER_LEN + segCount + 4) return null

            val bodyLen = HEADER_LEN + segCount
            val storedCrc = buf.getInt(bodyLen)
            if (computeCrc32c(raw, 0, bodyLen) != storedCrc) return null

            val vHash = raw.copyOfRange(40, 48)
            val states = ByteArray(segCount) { idx ->
                val s = raw[HEADER_LEN + idx]
                if (s == STATE_DONE) STATE_DONE else STATE_PENDING
            }
            return AetherBinaryLedger(totalLen, segBytes, vHash, states)
        }

        fun loadOrCreate(
            file: File,
            totalLen: Long,
            segmentBytes: Long,
            etagOrLastMod: String,
        ): AetherBinaryLedger {
            if (file.exists()) {
                val decoded = runCatching { decode(file.readBytes()) }.getOrNull()
                if (decoded != null && decoded.totalLen == totalLen) {
                    val expected = validatorHash(etagOrLastMod)
                    if (etagOrLastMod.isBlank() || decoded.validatorHash.contentEquals(expected)) {
                        return decoded
                    }
                }
            }
            val count = if (totalLen <= 0L) 1 else ((totalLen + segmentBytes - 1L) / segmentBytes).toInt()
            return AetherBinaryLedger(
                totalLen = totalLen,
                segmentBytes = segmentBytes,
                validatorHash = validatorHash(etagOrLastMod),
                states = ByteArray(count) { STATE_PENDING },
            ).also { it.persist(file) }
        }
    }
}
