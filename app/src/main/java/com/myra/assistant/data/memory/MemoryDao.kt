package com.myra.assistant.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AiriMemoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSemantic(row: SemanticMemoryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPerson(row: PersonEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAlias(row: PersonAliasEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRelationship(row: RelationshipEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEpisode(row: EpisodicMemoryEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEpisodeParticipants(rows: List<EpisodeParticipantEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertGoal(row: GoalMemoryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBehavior(row: BehaviorObservationEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun appendConversation(row: ConversationTruthEntity): Long

    @Query("SELECT * FROM airi_semantic_memory WHERE active = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun activeSemantic(limit: Int): List<SemanticMemoryEntity>
    @Query("SELECT * FROM airi_semantic_memory WHERE semanticKey = :key AND active = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun currentSemantic(key: String): SemanticMemoryEntity?
    @Query("SELECT * FROM airi_semantic_memory WHERE memoryId = :id LIMIT 1")
    suspend fun semanticById(id: String): SemanticMemoryEntity?
    @Query("SELECT * FROM airi_semantic_memory WHERE subjectEntityId = :entityId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun semanticByEntity(entityId: String): List<SemanticMemoryEntity>
    @Query("UPDATE airi_semantic_memory SET active = 0, supersededById = :replacementId, updatedAt = :at WHERE semanticKey = :key AND active = 1")
    suspend fun supersedeSemantic(key: String, replacementId: String, at: Long): Int
    @Query("UPDATE airi_semantic_memory SET active = 0, deletedAt = :at, updatedAt = :at WHERE memoryId = :id AND deletedAt IS NULL")
    suspend fun deleteSemantic(id: String, at: Long): Int
    @Query("UPDATE airi_semantic_memory SET lastAccessed = :at, accessCount = accessCount + 1 WHERE memoryId IN (:ids)")
    suspend fun touchSemantic(ids: List<String>, at: Long)

    @Query("SELECT * FROM airi_people WHERE active = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun activePeople(limit: Int): List<PersonEntity>
    @Query("SELECT * FROM airi_people WHERE entityId = :id AND active = 1 AND deletedAt IS NULL LIMIT 1")
    suspend fun personById(id: String): PersonEntity?
    @Query("SELECT p.* FROM airi_people p LEFT JOIN airi_person_aliases a ON p.entityId = a.entityId WHERE p.active = 1 AND p.deletedAt IS NULL AND (p.normalizedName = :name OR a.normalizedAlias = :name) GROUP BY p.entityId")
    suspend fun peopleByNormalizedName(name: String): List<PersonEntity>
    @Query("SELECT * FROM airi_person_aliases WHERE entityId = :entityId")
    suspend fun aliases(entityId: String): List<PersonAliasEntity>
    @Query("UPDATE airi_people SET canonicalName = :name, normalizedName = :normalized, updatedAt = :at WHERE entityId = :id AND active = 1")
    suspend fun renamePerson(id: String, name: String, normalized: String, at: Long): Int
    @Query("UPDATE airi_people SET active = 0, deletedAt = :at, updatedAt = :at WHERE entityId = :id AND active = 1")
    suspend fun deletePerson(id: String, at: Long): Int
    @Query("UPDATE airi_relationships SET active = 0, deletedAt = :at, updatedAt = :at WHERE targetEntityId = :id AND deletedAt IS NULL")
    suspend fun deletePersonRelationships(id: String, at: Long): Int
    @Query("UPDATE airi_semantic_memory SET active = 0, deletedAt = :at, updatedAt = :at WHERE subjectEntityId = :id AND deletedAt IS NULL")
    suspend fun deletePersonSemantic(id: String, at: Long): Int
    @Query("UPDATE airi_episodes SET deletedAt = :at WHERE episodeId IN (SELECT episodeId FROM airi_episode_participants WHERE entityId = :id) AND deletedAt IS NULL")
    suspend fun deletePersonEpisodes(id: String, at: Long): Int

    @Query("SELECT * FROM airi_relationships WHERE active = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun activeRelationships(limit: Int): List<RelationshipEntity>
    @Query("SELECT * FROM airi_relationships WHERE targetEntityId = :entityId AND active = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun relationshipsFor(entityId: String): List<RelationshipEntity>
    @Query("SELECT * FROM airi_relationships WHERE targetEntityId = :entityId AND relationshipType = :type AND active = 1 AND deletedAt IS NULL LIMIT 1")
    suspend fun currentRelationship(entityId: String, type: String): RelationshipEntity?
    @Query("UPDATE airi_relationships SET active = 0, supersededById = :replacementId, updatedAt = :at WHERE targetEntityId = :entityId AND active = 1")
    suspend fun supersedeRelationships(entityId: String, replacementId: String, at: Long): Int
    @Query("UPDATE airi_relationships SET active = 0, deletedAt = :at, updatedAt = :at WHERE targetEntityId = :entityId AND relationshipType = :type AND active = 1")
    suspend fun endRelationship(entityId: String, type: String, at: Long): Int
    @Query("UPDATE airi_relationships SET lastAccessed = :at, accessCount = accessCount + 1 WHERE relationshipId IN (:ids)")
    suspend fun touchRelationships(ids: List<String>, at: Long)

    @Query("SELECT * FROM airi_episodes WHERE deletedAt IS NULL ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recentEpisodes(limit: Int): List<EpisodicMemoryEntity>
    @Query("SELECT e.* FROM airi_episodes e JOIN airi_episode_participants p ON e.episodeId = p.episodeId WHERE p.entityId = :entityId AND e.deletedAt IS NULL ORDER BY e.occurredAt DESC LIMIT :limit")
    suspend fun episodesFor(entityId: String, limit: Int): List<EpisodicMemoryEntity>
    @Query("UPDATE airi_episodes SET lastAccessed = :at, accessCount = accessCount + 1 WHERE episodeId IN (:ids)")
    suspend fun touchEpisodes(ids: List<String>, at: Long)

    @Query("SELECT * FROM airi_goals WHERE deletedAt IS NULL AND status NOT IN ('COMPLETED','ABANDONED') ORDER BY priority DESC, updatedAt DESC LIMIT :limit")
    suspend fun activeGoals(limit: Int): List<GoalMemoryEntity>
    @Query("SELECT * FROM airi_goals WHERE stableKey = :key AND deletedAt IS NULL LIMIT 1")
    suspend fun goalByKey(key: String): GoalMemoryEntity?
    @Query("SELECT * FROM airi_behavior_patterns WHERE stableKey = :key LIMIT 1")
    suspend fun behavior(key: String): BehaviorObservationEntity?
    @Query("SELECT * FROM airi_behavior_patterns WHERE kind = :kind ORDER BY observationCount DESC LIMIT :limit")
    suspend fun behaviorByKind(kind: String, limit: Int): List<BehaviorObservationEntity>
    @Query("DELETE FROM airi_behavior_patterns WHERE stableKey = :key") suspend fun deleteBehavior(key: String): Int

    @Query("SELECT * FROM airi_conversation_truth WHERE sessionId = :sessionId ORDER BY sequence DESC LIMIT :limit")
    suspend fun recentConversation(sessionId: String, limit: Int): List<ConversationTruthEntity>
    @Query("SELECT COUNT(*) FROM airi_conversation_truth WHERE sessionId = :sessionId") suspend fun conversationCount(sessionId: String): Int

    @Query("DELETE FROM airi_semantic_memory") suspend fun clearSemantic()
    @Query("DELETE FROM airi_people") suspend fun clearPeople()
    @Query("DELETE FROM airi_episodes") suspend fun clearEpisodes()
    @Query("DELETE FROM airi_goals") suspend fun clearGoals()
    @Query("DELETE FROM airi_behavior_patterns") suspend fun clearBehavior()
    @Query("DELETE FROM airi_conversation_truth") suspend fun clearConversation()

    @Transaction suspend fun clearAllMemory() {
        clearSemantic(); clearPeople(); clearEpisodes(); clearGoals(); clearBehavior(); clearConversation()
    }
}
