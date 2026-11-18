package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cas_chunks")
data class CasChunkEntity(
    @PrimaryKey val chunkHash: String,
    val chunkSize: Long,
    val sourceDownloadId: String,
    val lastVerifiedAt: Long = System.currentTimeMillis(),
)
