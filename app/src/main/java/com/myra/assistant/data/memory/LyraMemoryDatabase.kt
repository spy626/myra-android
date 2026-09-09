package com.myra.assistant.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The one local storage truth for the AIRI-based memory runtime. */
@Database(
    entities = [SemanticMemoryEntity::class, PersonEntity::class, PersonAliasEntity::class,
        RelationshipEntity::class, EpisodicMemoryEntity::class, EpisodeParticipantEntity::class,
        GoalMemoryEntity::class, BehaviorObservationEntity::class, ConversationTruthEntity::class],
    version = 4,
    exportSchema = false
)
abstract class LyraMemoryDatabase : RoomDatabase() {
    abstract fun airiMemoryDao(): AiriMemoryDao

    companion object {
        @Volatile private var instance: LyraMemoryDatabase? = null

        fun get(context: Context): LyraMemoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LyraMemoryDatabase::class.java, "lyra_memory.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN useCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) { MIGRATION_2_3_SQL.forEach(db::execSQL) }
        }
        val MIGRATION_2_3_SQL = listOf(
            "ALTER TABLE memories ADD COLUMN provenance TEXT NOT NULL DEFAULT 'LEGACY'",
            "ALTER TABLE memories ADD COLUMN lifecycleStatus TEXT NOT NULL DEFAULT 'ACTIVE'",
            "ALTER TABLE memories ADD COLUMN supersededById TEXT",
            "ALTER TABLE memories ADD COLUMN entityId TEXT",
            "ALTER TABLE memories ADD COLUMN entityName TEXT",
            "ALTER TABLE memories ADD COLUMN lastRecalledAt INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE memories ADD COLUMN observationMetadata TEXT",
            "CREATE TABLE IF NOT EXISTS behavior_observations (`id` TEXT NOT NULL, `stableKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `label` TEXT NOT NULL, `observationCount` INTEGER NOT NULL, `sessionCount` INTEGER NOT NULL, `dayCount` INTEGER NOT NULL, `firstObservedAt` INTEGER NOT NULL, `lastObservedAt` INTEGER NOT NULL, `lastSessionId` TEXT NOT NULL, `lastDayBucket` INTEGER NOT NULL, `metadata` TEXT, `promotedMemoryId` TEXT, PRIMARY KEY(`id`))"
        )

        /** Explicit one-time cutover. Old generic rows are imported as legacy semantic history. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AIRI_SCHEMA_SQL.forEach(db::execSQL)
                db.execSQL("INSERT INTO airi_semantic_memory(memoryId,semanticKey,category,statement,normalizedStatement,subjectEntityId,temporalScope,confidence,importance,explicit,provenance,sourceTurnId,sourceUtteranceId,active,supersededById,createdAt,updatedAt,lastAccessed,accessCount,deletedAt) SELECT id,stableKey,category,fact,normalizedFact,entityId,CASE WHEN lifecycleStatus='HISTORICAL' THEN 'HISTORICAL' ELSE 'CURRENT' END,confidence,5,1,provenance,0,'migration-v3',active,supersededById,createdAt,updatedAt,lastRecalledAt,useCount,NULL FROM memories")
                db.execSQL("DROP TABLE behavior_observations")
                db.execSQL("DROP TABLE memories")
            }
        }

        val AIRI_SCHEMA_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS airi_semantic_memory (`memoryId` TEXT NOT NULL, `semanticKey` TEXT NOT NULL, `category` TEXT NOT NULL, `statement` TEXT NOT NULL, `normalizedStatement` TEXT NOT NULL, `subjectEntityId` TEXT, `temporalScope` TEXT NOT NULL, `confidence` REAL NOT NULL, `importance` INTEGER NOT NULL, `explicit` INTEGER NOT NULL, `provenance` TEXT NOT NULL, `sourceTurnId` INTEGER NOT NULL, `sourceUtteranceId` TEXT NOT NULL, `active` INTEGER NOT NULL, `supersededById` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastAccessed` INTEGER NOT NULL, `accessCount` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`memoryId`))",
            "CREATE INDEX IF NOT EXISTS index_airi_semantic_memory_semanticKey_active ON airi_semantic_memory(semanticKey,active)",
            "CREATE INDEX IF NOT EXISTS index_airi_semantic_memory_category_active ON airi_semantic_memory(category,active)",
            "CREATE INDEX IF NOT EXISTS index_airi_semantic_memory_subjectEntityId ON airi_semantic_memory(subjectEntityId)",
            "CREATE INDEX IF NOT EXISTS index_airi_semantic_memory_updatedAt ON airi_semantic_memory(updatedAt)",
            "CREATE INDEX IF NOT EXISTS index_airi_semantic_memory_lastAccessed ON airi_semantic_memory(lastAccessed)",
            "CREATE TABLE IF NOT EXISTS airi_people (`entityId` TEXT NOT NULL, `canonicalName` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastAccessed` INTEGER NOT NULL, `accessCount` INTEGER NOT NULL, `active` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`entityId`))",
            "CREATE INDEX IF NOT EXISTS index_airi_people_canonicalName ON airi_people(canonicalName)", "CREATE INDEX IF NOT EXISTS index_airi_people_active ON airi_people(active)",
            "CREATE TABLE IF NOT EXISTS airi_person_aliases (`aliasId` TEXT NOT NULL, `entityId` TEXT NOT NULL, `alias` TEXT NOT NULL, `normalizedAlias` TEXT NOT NULL, `sourceTurnId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`aliasId`), FOREIGN KEY(`entityId`) REFERENCES `airi_people`(`entityId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS index_airi_person_aliases_entityId ON airi_person_aliases(entityId)", "CREATE INDEX IF NOT EXISTS index_airi_person_aliases_normalizedAlias ON airi_person_aliases(normalizedAlias)",
            "CREATE TABLE IF NOT EXISTS airi_relationships (`relationshipId` TEXT NOT NULL, `targetEntityId` TEXT NOT NULL, `relationshipType` TEXT NOT NULL, `temporalScope` TEXT NOT NULL, `confidence` REAL NOT NULL, `provenance` TEXT NOT NULL, `sourceTurnId` INTEGER NOT NULL, `sourceUtteranceId` TEXT NOT NULL, `active` INTEGER NOT NULL, `supersededById` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastAccessed` INTEGER NOT NULL, `accessCount` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`relationshipId`), FOREIGN KEY(`targetEntityId`) REFERENCES `airi_people`(`entityId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS index_airi_relationships_targetEntityId ON airi_relationships(targetEntityId)", "CREATE INDEX IF NOT EXISTS index_airi_relationships_relationshipType_active ON airi_relationships(relationshipType,active)", "CREATE INDEX IF NOT EXISTS index_airi_relationships_updatedAt ON airi_relationships(updatedAt)",
            "CREATE TABLE IF NOT EXISTS airi_episodes (`episodeId` TEXT NOT NULL, `eventType` TEXT NOT NULL, `summary` TEXT NOT NULL, `normalizedSummary` TEXT NOT NULL, `temporalScope` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `confidence` REAL NOT NULL, `importance` INTEGER NOT NULL, `provenance` TEXT NOT NULL, `sourceTurnId` INTEGER NOT NULL, `sourceUtteranceId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `lastAccessed` INTEGER NOT NULL, `accessCount` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`episodeId`))",
            "CREATE INDEX IF NOT EXISTS index_airi_episodes_eventType ON airi_episodes(eventType)", "CREATE INDEX IF NOT EXISTS index_airi_episodes_occurredAt ON airi_episodes(occurredAt)", "CREATE INDEX IF NOT EXISTS index_airi_episodes_sourceTurnId ON airi_episodes(sourceTurnId)",
            "CREATE TABLE IF NOT EXISTS airi_episode_participants (`episodeId` TEXT NOT NULL, `entityId` TEXT NOT NULL, PRIMARY KEY(`episodeId`,`entityId`), FOREIGN KEY(`episodeId`) REFERENCES `airi_episodes`(`episodeId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`entityId`) REFERENCES `airi_people`(`entityId`) ON UPDATE NO ACTION ON DELETE CASCADE )", "CREATE INDEX IF NOT EXISTS index_airi_episode_participants_entityId ON airi_episode_participants(entityId)",
            "CREATE TABLE IF NOT EXISTS airi_goals (`goalId` TEXT NOT NULL, `stableKey` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `status` TEXT NOT NULL, `priority` INTEGER NOT NULL, `progress` INTEGER NOT NULL, `deadline` INTEGER, `parentGoalId` TEXT, `category` TEXT NOT NULL, `provenance` TEXT NOT NULL, `sourceTurnId` INTEGER NOT NULL, `sourceUtteranceId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastAccessed` INTEGER NOT NULL, `accessCount` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`goalId`))",
            "CREATE INDEX IF NOT EXISTS index_airi_goals_stableKey_status ON airi_goals(stableKey,status)", "CREATE INDEX IF NOT EXISTS index_airi_goals_updatedAt ON airi_goals(updatedAt)", "CREATE INDEX IF NOT EXISTS index_airi_goals_parentGoalId ON airi_goals(parentGoalId)",
            "CREATE TABLE IF NOT EXISTS airi_behavior_patterns (`patternId` TEXT NOT NULL, `stableKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `label` TEXT NOT NULL, `observationCount` INTEGER NOT NULL, `sessionCount` INTEGER NOT NULL, `dayCount` INTEGER NOT NULL, `firstObservedAt` INTEGER NOT NULL, `lastObservedAt` INTEGER NOT NULL, `lastSessionId` TEXT NOT NULL, `lastDayBucket` INTEGER NOT NULL, `state` TEXT NOT NULL, `confidence` REAL NOT NULL, `importance` INTEGER NOT NULL, `metadata` TEXT, PRIMARY KEY(`patternId`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_airi_behavior_patterns_stableKey ON airi_behavior_patterns(stableKey)", "CREATE INDEX IF NOT EXISTS index_airi_behavior_patterns_kind_lastObservedAt ON airi_behavior_patterns(kind,lastObservedAt)", "CREATE INDEX IF NOT EXISTS index_airi_behavior_patterns_state ON airi_behavior_patterns(state)",
            "CREATE TABLE IF NOT EXISTS airi_conversation_truth (`messageId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `turnId` INTEGER NOT NULL, `utteranceId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `committedAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_airi_conversation_truth_sessionId_sequence ON airi_conversation_truth(sessionId,sequence)", "CREATE INDEX IF NOT EXISTS index_airi_conversation_truth_turnId ON airi_conversation_truth(turnId)", "CREATE INDEX IF NOT EXISTS index_airi_conversation_truth_committedAt ON airi_conversation_truth(committedAt)"
        )
    }
}
