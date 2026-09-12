package com.myra.assistant.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The single local storage truth for the AIRI/Plast-Mem Android port.
 *
 * Version 6 is an explicit pre-release destructive memory cutover. Versions 1-5
 * contained incompatible Memory V2 or JARVIS test schemas and are intentionally
 * not imported. App UI/preferences are outside this database. A clean reinstall
 * is required for phone acceptance and future production releases must replace
 * this pre-release fallback with a reviewed migration.
 */
@Database(
    entities = [
        SemanticMemoryEntity::class, PersonEntity::class, PersonAliasEntity::class,
        RelationshipEntity::class, EpisodicMemoryEntity::class, EpisodeParticipantEntity::class,
        GoalMemoryEntity::class, BehaviorObservationEntity::class, ConversationTruthEntity::class,
        SegmentationStateEntity::class, EpisodeSpanEntity::class, PendingReviewEntity::class,
        SemanticProvenanceEntity::class, SparkTraceEntity::class,
        SemanticMemoryFtsEntity::class, EpisodicMemoryFtsEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class LyraMemoryDatabase : RoomDatabase() {
    abstract fun airiMemoryDao(): AiriMemoryDao

    companion object {
        @Volatile private var instance: LyraMemoryDatabase? = null

        fun get(context: Context): LyraMemoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LyraMemoryDatabase::class.java,
                "lyra_memory.db"
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
