package com.example.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Crash-safe AETHLDG2 binary ledger byte-for-byte compatible with `rust/aether-core/src/ledger.rs`
 * (and backward-compatible decoding legacy AETHLDG1 ledgers).
 */
class AetherBinaryLedger(
    val totalLen: Long,
    val segmentBytes: Long,
    val validatorHash: ByteArray,
    val states: ByteArray,
    var contiguousVerifiedOffset: Long = 0L,
    val slabDigestsRaw: ByteArray = ByteArray(0),
) {
    val slabDigestCount: Int
        get() = slabDigestsRaw.size / SLAB_DIGEST_BYTES

    fun encode(): ByteArray {
        val bodyLen = HEADER_LEN_V2 + states.size + slabDigestsRaw.size
        val buf = ByteBuffer.allocate(bodyLen + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC_V2)
        buf.putInt(VERSION_V2)
        buf.putInt(0)
        buf.putLong(totalLen)
        buf.putLong(segmentBytes)
        buf.putInt(states.size)
        buf.putInt(slabDigestCount)
        buf.put(validatorHash.copyOf(8))
        buf.putLong(contiguousVerifiedOffset)
        buf.put(ByteArray(16))
        buf.put(states)
        if (slabDigestsRaw.isNotEmpty()) {
            buf.put(slabDigestsRaw)
        }
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

    /**
     * Resets any crashed STATE_LEASED entries back to STATE_PENDING cleanly.
     * Returns true if at least one leased segment was rolled back.
     */
    @Synchronized
    fun rollbackLeasedSegments(): Boolean {
        var changed = false
        for (i in states.indices) {
            if (states[i] == STATE_LEASED) {
                states[i] = STATE_PENDING
                changed = true
            }
        }
        return changed
    }

    companion object {
        val MAGIC_V1 = "AETHLDG1".toByteArray(Charsets.US_ASCII)
        val MAGIC_V2 = "AETHLDG2".toByteArray(Charsets.US_ASCII)
        const val VERSION_V1 = 1
        const val VERSION_V2 = 2
        const val HEADER_LEN_V1 = 64
        const val HEADER_LEN_V2 = 72
        const val HEADER_LEN = HEADER_LEN_V2
        const val SLAB_DIGEST_BYTES = 32

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
            if (raw.size < HEADER_LEN_V1 + 4) return null
            val magic = raw.copyOfRange(0, 8)
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

            if (magic.contentEquals(MAGIC_V2)) {
                if (raw.size < HEADER_LEN_V2 + 4) return null
                val version = buf.getInt(8)
                if (version != VERSION_V2) return null
                val totalLen = buf.getLong(16)
                val segBytes = buf.getLong(24)
                val segCount = buf.getInt(32)
                val digestCount = buf.getInt(36)
                if (segCount < 0 || digestCount < 0) return null
                val expectedBody = HEADER_LEN_V2 + segCount + (digestCount * SLAB_DIGEST_BYTES)
                if (raw.size != expectedBody + 4) return null
                val storedCrc = buf.getInt(expectedBody)
                if (computeCrc32c(raw, 0, expectedBody) != storedCrc) return null

                val vHash = raw.copyOfRange(40, 48)
                val contiguous = buf.getLong(48)
                val states = ByteArray(segCount) { idx ->
                    val s = raw[HEADER_LEN_V2 + idx]
                    if (s == STATE_DONE) STATE_DONE else STATE_PENDING
                }
                val digestsStart = HEADER_LEN_V2 + segCount
                val digestsRaw = raw.copyOfRange(digestsStart, expectedBody)
                return AetherBinaryLedger(
                    totalLen = totalLen,
                    segmentBytes = segBytes,
                    validatorHash = vHash,
                    states = states,
                    contiguousVerifiedOffset = contiguous,
                    slabDigestsRaw = digestsRaw,
                )
            }

            if (magic.contentEquals(MAGIC_V1)) {
                val version = buf.getInt(8)
                if (version != VERSION_V1) return null
                val totalLen = buf.getLong(16)
                val segBytes = buf.getLong(24)
                val segCount = buf.getInt(32)
                if (segCount < 0 || raw.size != HEADER_LEN_V1 + segCount + 4) return null

                val bodyLen = HEADER_LEN_V1 + segCount
                val storedCrc = buf.getInt(bodyLen)
                if (computeCrc32c(raw, 0, bodyLen) != storedCrc) return null

                val vHash = raw.copyOfRange(40, 48)
                val states = ByteArray(segCount) { idx ->
                    val s = raw[HEADER_LEN_V1 + idx]
                    if (s == STATE_DONE) STATE_DONE else STATE_PENDING
                }
                return AetherBinaryLedger(totalLen, segBytes, vHash, states)
            }

            return null
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
