package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadEntity::class, CasChunkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun casChunkDao(): CasChunkDao

    companion object {
        @Volatile
        private var INSTANCE: AetherDatabase? = null

        fun getInstance(context: Context): AetherDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AetherDatabase::class.java,
                    "aetherio_omega.db",
                ).build().also { INSTANCE = it }
            }
    }
}
