package com.myra.assistant.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MemoryEntity::class], version = 1, exportSchema = false)
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
                ).build().also { instance = it }
            }
    }
}
