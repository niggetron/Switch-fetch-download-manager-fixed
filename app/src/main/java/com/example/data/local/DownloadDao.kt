package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun observeCompleted(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun getAllOnce(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Update
    suspend fun update(entity: DownloadEntity)

    @Query("UPDATE downloads SET actualMd5 = :md5, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMd5(id: String, md5: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET actualSha256 = :sha256, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSha256(id: String, sha256: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CasChunkDao {
    @Query("SELECT COUNT(*) FROM cas_chunks")
    fun observeChunkCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(chunkSize), 0) FROM cas_chunks")
    fun observeTotalCasBytes(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<CasChunkEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM cas_chunks WHERE chunkHash = :hash)")
    suspend fun hasChunk(hash: String): Boolean

    @Query("DELETE FROM cas_chunks WHERE sourceDownloadId = :downloadId")
    suspend fun deleteByDownload(downloadId: String)

    @Query("DELETE FROM cas_chunks")
    suspend fun clearAll()
}
