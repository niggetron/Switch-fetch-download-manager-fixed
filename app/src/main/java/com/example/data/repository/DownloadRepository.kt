package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.example.data.local.CasChunkDao
import com.example.data.local.CasChunkEntity
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class StorageStats(
    val freeBytes: Long,
    val totalBytes: Long,
    val downloadedFilesCount: Int,
    val downloadedBytesTotal: Long,
)

class DownloadRepository(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val casChunkDao: CasChunkDao,
) {
    val allDownloads: Flow<List<DownloadEntity>> = downloadDao.observeAll()
    val completedDownloads: Flow<List<DownloadEntity>> = downloadDao.observeCompleted()
    val casChunkCount: Flow<Int> = casChunkDao.observeChunkCount()
    val casTotalBytes: Flow<Long> = casChunkDao.observeTotalCasBytes()

    fun getDownloadsDirectory(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun resolveUniqueTargetFile(desiredName: String): File {
        val safeName = desiredName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "download_${System.currentTimeMillis()}.bin" }
        val dir = getDownloadsDirectory()
        var candidate = File(dir, safeName)
        if (!candidate.exists()) return candidate

        val dotIdx = safeName.lastIndexOf('.')
        val stem = if (dotIdx > 0) safeName.substring(0, dotIdx) else safeName
        val ext = if (dotIdx > 0) safeName.substring(dotIdx) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${stem}_($index)$ext")
            index++
        }
        return candidate
    }

    fun queryStorageStats(completedList: List<DownloadEntity>): StorageStats {
        val dir = getDownloadsDirectory()
        val stat = StatFs(dir.absolutePath)
        return StorageStats(
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
            downloadedFilesCount = completedList.size,
            downloadedBytesTotal = completedList.sumOf { it.totalBytes.coerceAtLeast(it.downloadedBytes) },
        )
    }

    suspend fun getById(id: String): DownloadEntity? = downloadDao.getById(id)

    suspend fun getAllOnce(): List<DownloadEntity> = downloadDao.getAllOnce()

    suspend fun upsert(entity: DownloadEntity) = downloadDao.upsert(entity)

    suspend fun update(entity: DownloadEntity) = downloadDao.update(entity)

    suspend fun recordCasChunks(chunks: List<CasChunkEntity>) {
        if (chunks.isNotEmpty()) casChunkDao.insertAll(chunks)
    }

    suspend fun hasCasChunk(hash: String): Boolean = casChunkDao.hasChunk(hash)

    suspend fun pruneCasCache() {
        casChunkDao.clearAll()
    }

    suspend fun deleteDownload(id: String, deleteBackingFile: Boolean) {
        val existing = downloadDao.getById(id)
        if (deleteBackingFile && existing != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (existing.isSafDestination) {
                        val uri = Uri.parse(existing.destinationUri)
                        context.contentResolver.delete(uri, null, null)
                    } else {
                        val file = File(existing.destinationUri)
                        if (file.exists()) file.delete()
                    }
                }
            }
        }
        casChunkDao.deleteByDownload(id)
        downloadDao.deleteById(id)
    }
}
