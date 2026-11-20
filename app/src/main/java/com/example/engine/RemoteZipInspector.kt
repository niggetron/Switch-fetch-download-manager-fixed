package com.example.engine

import com.example.data.model.RemoteZipEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * v4.0-A Remote `.zip` EOCD 64 KB Suffix Inspector & Selective Extractor:
 * Fetches ONLY the last 65,536 bytes (`Range: bytes=-65536`) of a remote `.zip` archive,
 * locates the End of Central Directory (0x06054b50), reads the Central Directory entries
 * (0x02014b50), and allows selectively extracting individual files via exact byte-range HTTP
 * requests without downloading the entire multi-gigabyte archive!
 */
object RemoteZipInspector {

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CD_HEADER_SIGNATURE = 0x02014b50
    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
    private const val SUFFIX_WINDOW = 65_536L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun inspectRemoteZip(url: String): Result<List<RemoteZipEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(url.trim())
                .header("Range", "bytes=-$SUFFIX_WINDOW")
                .header("Accept-Encoding", "identity")
                .build()

            val (tailBytes, totalSize) = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    throw IOException("HTTP ${resp.code} while probing remote ZIP EOCD")
                }
                val rangeHeader = resp.header("Content-Range")
                val total = rangeHeader?.substringAfterLast('/')?.toLongOrNull()
                    ?: resp.header("Content-Length")?.toLongOrNull()
                    ?: -1L
                val bytes = resp.body?.bytes() ?: throw IOException("Empty EOCD response body")
                bytes to total
            }

            val eocdPos = findEocdOffset(tailBytes)
                ?: throw IOException("ZIP End-of-Central-Directory signature not found in trailing 64 KB")

            val eocdBuf = ByteBuffer.wrap(tailBytes, eocdPos, tailBytes.size - eocdPos)
                .order(ByteOrder.LITTLE_ENDIAN)
            eocdBuf.int // signature
            eocdBuf.short // disk num
            eocdBuf.short // start disk
            eocdBuf.short // entries on disk
            val totalEntries = eocdBuf.short.toInt() and 0xFFFF
            val cdSize = eocdBuf.int.toLong() and 0xFFFFFFFFL
            val cdOffset = eocdBuf.int.toLong() and 0xFFFFFFFFL

            if (cdSize <= 0L || cdSize > 32L * 1024L * 1024L) {
                throw IOException("Unsupported ZIP64 or oversized Central Directory ($cdSize bytes)")
            }

            val tailStartOffset = if (totalSize > 0L) (totalSize - tailBytes.size).coerceAtLeast(0L) else 0L
            val cdBytes: ByteArray = if (totalSize > 0L && cdOffset >= tailStartOffset &&
                (cdOffset - tailStartOffset + cdSize) <= tailBytes.size
            ) {
                val rel = (cdOffset - tailStartOffset).toInt()
                tailBytes.copyOfRange(rel, rel + cdSize.toInt())
            } else {
                val cdReq = Request.Builder()
                    .url(url.trim())
                    .header("Range", "bytes=$cdOffset-${cdOffset + cdSize - 1L}")
                    .header("Accept-Encoding", "identity")
                    .build()
                client.newCall(cdReq).execute().use { cdResp ->
                    if (!cdResp.isSuccessful && cdResp.code != 206) {
                        throw IOException("HTTP ${cdResp.code} reading ZIP Central Directory")
                    }
                    cdResp.body?.bytes() ?: throw IOException("Empty Central Directory body")
                }
            }

            parseCentralDirectory(cdBytes, totalEntries)
        }
    }

    /**
     * Selectively downloads and inflates a single entry from a remote `.zip` using only
     * its byte range `[localHeaderOffset .. localHeaderOffset + header + compressedSize]`.
     */
    suspend fun extractRemoteZipEntry(
        url: String,
        entry: RemoteZipEntry,
        targetFile: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val headerProbeEnd = entry.localHeaderOffset + 30L + entry.fileName.toByteArray().size + 1024L
        val totalEnd = entry.localHeaderOffset + 30L + entry.fileName.toByteArray().size + 1024L + entry.compressedSize
        val req = Request.Builder()
            .url(url.trim())
            .header("Range", "bytes=${entry.localHeaderOffset}-${maxOf(headerProbeEnd, totalEnd)}")
            .header("Accept-Encoding", "identity")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                throw IOException("HTTP ${resp.code} fetching ZIP entry ${entry.fileName}")
            }
            val raw = resp.body?.bytes() ?: throw IOException("Empty ZIP entry payload")
            if (raw.size < 30) throw IOException("Truncated local ZIP header")
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.int != LOCAL_HEADER_SIGNATURE) throw IOException("Invalid local ZIP header signature")
            buf.position(26)
            val nameLen = buf.short.toInt() and 0xFFFF
            val extraLen = buf.short.toInt() and 0xFFFF
            val dataOffset = 30 + nameLen + extraLen
            val compLen = entry.compressedSize.toInt().coerceAtMost((raw.size - dataOffset).coerceAtLeast(0))

            targetFile.parentFile?.mkdirs()
            var written = 0L
            FileOutputStream(targetFile).use { fos ->
                if (entry.isStored) {
                    fos.write(raw, dataOffset, compLen)
                    written = compLen.toLong()
                    onProgress(written, entry.uncompressedSize.coerceAtLeast(written))
                } else if (entry.isDeflated) {
                    val inflater = Inflater(true)
                    try {
                        InflaterInputStream(raw.inputStream(dataOffset, compLen), inflater).use { iis ->
                            val chunk = ByteArray(64 * 1024)
                            while (true) {
                                val r = iis.read(chunk)
                                if (r == -1) break
                                fos.write(chunk, 0, r)
                                written += r
                                onProgress(written, entry.uncompressedSize.coerceAtLeast(written))
                            }
                        }
                    } finally {
                        inflater.end()
                    }
                } else {
                    throw IOException("Unsupported ZIP compression method ${entry.compressionMethod}")
                }
                fos.fd.sync()
            }
            written
        }
    }

    internal fun findEocdOffset(data: ByteArray): Int? {
        if (data.size < 22) return null
        val start = data.size - 22
        val min = (data.size - 65_557).coerceAtLeast(0)
        for (i in start downTo min) {
            val sig = (data[i].toInt() and 0xFF) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                ((data[i + 2].toInt() and 0xFF) shl 16) or
                ((data[i + 3].toInt() and 0xFF) shl 24)
            if (sig == EOCD_SIGNATURE) return i
        }
        return null
    }

    internal fun parseCentralDirectory(cdBytes: ByteArray, expectedCount: Int): List<RemoteZipEntry> {
        val entries = mutableListOf<RemoteZipEntry>()
        val buf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 46 && entries.size < expectedCount.coerceAtLeast(1024)) {
            val sig = buf.int
            if (sig != CD_HEADER_SIGNATURE) break
            buf.short // version made by
            buf.short // version needed
            buf.short // flags
            val method = buf.short.toInt() and 0xFFFF
            buf.short // mod time
            buf.short // mod date
            val crc32 = buf.int.toLong() and 0xFFFFFFFFL
            val compSize = buf.int.toLong() and 0xFFFFFFFFL
            val uncompSize = buf.int.toLong() and 0xFFFFFFFFL
            val nameLen = buf.short.toInt() and 0xFFFF
            val extraLen = buf.short.toInt() and 0xFFFF
            val commentLen = buf.short.toInt() and 0xFFFF
            buf.short // disk start
            buf.short // internal attrs
            buf.int // external attrs
            val localOffset = buf.int.toLong() and 0xFFFFFFFFL
            if (buf.remaining() < nameLen + extraLen + commentLen) break
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            buf.position(buf.position() + extraLen + commentLen)
            val name = String(nameBytes, Charsets.UTF_8)
            if (!name.endsWith("/")) {
                entries += RemoteZipEntry(
                    fileName = name,
                    compressedSize = compSize,
                    uncompressedSize = uncompSize,
                    localHeaderOffset = localOffset,
                    compressionMethod = method,
                    crc32 = crc32,
                )
            }
        }
        return entries.sortedByDescending { it.uncompressedSize }
    }
}
