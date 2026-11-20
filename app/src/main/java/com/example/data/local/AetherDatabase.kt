package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadEntity::class, CasChunkEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun casChunkDao(): CasChunkDao

    companion object {
        @Volatile
        private var INSTANCE: AetherDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN mirrorUrls TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN engineBackendUsed TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN wifiBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN cellBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN maxCellularBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN browserProfile TEXT NOT NULL DEFAULT 'chrome134'")
                db.execSQL("ALTER TABLE downloads ADD COLUMN protocolMode TEXT NOT NULL DEFAULT 'auto'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN startedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN activeDownloadMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN maxConnectionsUsed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN wifi2Bytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN usbBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN actualMd5 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE downloads ADD COLUMN streamType TEXT NOT NULL DEFAULT 'DIRECT'")
            }
        }

        fun getInstance(context: Context): AetherDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AetherDatabase::class.java,
                    "aetherio_omega.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
