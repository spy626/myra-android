package com.myra.assistant.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MemoryEntity::class], version = 2, exportSchema = false)
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
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE memories ADD COLUMN useCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE memories ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
