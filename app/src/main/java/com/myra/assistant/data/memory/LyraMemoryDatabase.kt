package com.myra.assistant.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MemoryEntity::class, BehaviorObservationEntity::class], version = 3, exportSchema = false)
abstract class LyraMemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile private var instance: LyraMemoryDatabase? = null

        fun get(context: Context): LyraMemoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LyraMemoryDatabase::class.java,
                    "lyra_memory.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE memories ADD COLUMN useCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE memories ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Additive only: every v2 memory row remains active and addressable. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE memories ADD COLUMN provenance TEXT NOT NULL DEFAULT 'LEGACY'")
                database.execSQL("ALTER TABLE memories ADD COLUMN lifecycleStatus TEXT NOT NULL DEFAULT 'ACTIVE'")
                database.execSQL("ALTER TABLE memories ADD COLUMN supersededById TEXT")
                database.execSQL("ALTER TABLE memories ADD COLUMN entityId TEXT")
                database.execSQL("ALTER TABLE memories ADD COLUMN entityName TEXT")
                database.execSQL("ALTER TABLE memories ADD COLUMN lastRecalledAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE memories ADD COLUMN observationMetadata TEXT")
                database.execSQL("UPDATE memories SET lastRecalledAt = lastUsedAt")
                database.execSQL("UPDATE memories SET lifecycleStatus = 'INACTIVE' WHERE active = 0")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_memories_entityId ON memories(entityId)")
                database.execSQL("CREATE TABLE IF NOT EXISTS behavior_observations (`id` TEXT NOT NULL, `stableKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `label` TEXT NOT NULL, `observationCount` INTEGER NOT NULL, `sessionCount` INTEGER NOT NULL, `dayCount` INTEGER NOT NULL, `firstObservedAt` INTEGER NOT NULL, `lastObservedAt` INTEGER NOT NULL, `lastSessionId` TEXT NOT NULL, `lastDayBucket` INTEGER NOT NULL, `metadata` TEXT, `promotedMemoryId` TEXT, PRIMARY KEY(`id`))")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_behavior_observations_stableKey ON behavior_observations(stableKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_behavior_observations_kind_lastObservedAt ON behavior_observations(kind, lastObservedAt)")
            }
        }
    }
}
