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

        /**
         * Kept as one explicit SQL contract so the exact production migration can also
         * be exercised against a real SQLite engine in JVM tests.
         */
        val MIGRATION_2_3_SQL = listOf(
            "ALTER TABLE memories ADD COLUMN provenance TEXT NOT NULL DEFAULT 'LEGACY'",
            "ALTER TABLE memories ADD COLUMN lifecycleStatus TEXT NOT NULL DEFAULT 'ACTIVE'",
            "ALTER TABLE memories ADD COLUMN supersededById TEXT",
            "ALTER TABLE memories ADD COLUMN entityId TEXT",
            "ALTER TABLE memories ADD COLUMN entityName TEXT",
            "ALTER TABLE memories ADD COLUMN lastRecalledAt INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE memories ADD COLUMN observationMetadata TEXT",
            "UPDATE memories SET lastRecalledAt = lastUsedAt",
            "UPDATE memories SET lifecycleStatus = 'INACTIVE' WHERE active = 0",
            "CREATE INDEX IF NOT EXISTS index_memories_entityId ON memories(entityId)",
            "CREATE TABLE IF NOT EXISTS behavior_observations (`id` TEXT NOT NULL, `stableKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `label` TEXT NOT NULL, `observationCount` INTEGER NOT NULL, `sessionCount` INTEGER NOT NULL, `dayCount` INTEGER NOT NULL, `firstObservedAt` INTEGER NOT NULL, `lastObservedAt` INTEGER NOT NULL, `lastSessionId` TEXT NOT NULL, `lastDayBucket` INTEGER NOT NULL, `metadata` TEXT, `promotedMemoryId` TEXT, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_behavior_observations_stableKey ON behavior_observations(stableKey)",
            "CREATE INDEX IF NOT EXISTS index_behavior_observations_kind_lastObservedAt ON behavior_observations(kind, lastObservedAt)"
        )

        /** Additive only: every v2 memory row remains addressable. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                for (statement in MIGRATION_2_3_SQL) database.execSQL(statement)
            }
        }
    }
}
