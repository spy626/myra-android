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
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSegmentationState(row: SegmentationStateEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEpisodeSpan(row: EpisodeSpanEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSemanticProvenance(rows: List<SemanticProvenanceEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertConsolidationAction(row: ConsolidationActionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSemanticFts(row: SemanticMemoryFtsEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEpisodeFts(row: EpisodicMemoryFtsEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueueReview(row: PendingReviewEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertBackgroundWork(row: MemoryBackgroundWorkEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun appendSparkTrace(row: SparkTraceEntity): Long

    @Query("SELECT * FROM airi_semantic_memory WHERE active = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun activeSemantic(limit: Int): List<SemanticMemoryEntity>
    @Query("SELECT * FROM airi_semantic_memory WHERE category = :category AND active = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun activeSemanticByCategory(category: String, limit: Int): List<SemanticMemoryEntity>
    @Query("SELECT * FROM airi_semantic_memory WHERE semanticKey = :key AND active = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun currentSemantic(key: String): SemanticMemoryEntity?
    @Query("SELECT * FROM airi_semantic_memory WHERE memoryId = :id LIMIT 1")
    suspend fun semanticById(id: String): SemanticMemoryEntity?
    @Query("SELECT * FROM airi_semantic_memory WHERE subjectEntityId = :entityId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun semanticByEntity(entityId: String): List<SemanticMemoryEntity>
    @Query("SELECT * FROM airi_semantic_memory WHERE conversationId = :conversationId AND invalidAt IS NULL AND active = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun activeSemanticForConversation(conversationId: String, limit: Int): List<SemanticMemoryEntity>
    @Query("SELECT DISTINCT s.memoryId FROM airi_semantic_memory s JOIN airi_conversation_truth c ON c.sessionId = :conversationId AND c.turnId = s.sourceTurnId WHERE c.sequence BETWEEN :start AND :end")
    suspend fun semanticIdsForConversationRange(conversationId: String, start: Long, end: Long): List<String>
    @Query("UPDATE airi_consolidation_actions SET episodeId = :episodeId, calibratedAt = :at WHERE conversationId = :conversationId AND turnId IN (SELECT turnId FROM airi_conversation_truth WHERE sessionId = :conversationId AND sequence BETWEEN :start AND :end) AND calibratedAt IS NULL")
    suspend fun calibrateActionsForRange(conversationId: String, start: Long, end: Long, episodeId: String, at: Long): Int
    @Query("SELECT s.* FROM airi_semantic_memory s JOIN airi_semantic_fts f ON s.memoryId = f.memoryId WHERE airi_semantic_fts MATCH :query AND s.active = 1 AND s.invalidAt IS NULL AND s.deletedAt IS NULL LIMIT :limit")
    suspend fun searchSemanticFts(query: String, limit: Int): List<SemanticMemoryEntity>
    @Query("UPDATE airi_semantic_memory SET active = 0, supersededById = :replacementId, updatedAt = :at WHERE semanticKey = :key AND active = 1")
    suspend fun supersedeSemantic(key: String, replacementId: String, at: Long): Int
    @Query("UPDATE airi_semantic_memory SET active = 0, invalidAt = :at, updatedAt = :at WHERE semanticKey = :key AND active = 1 AND invalidAt IS NULL")
    suspend fun invalidateSemantic(key: String, at: Long): Int
    @Query("UPDATE airi_semantic_memory SET confidence = MIN(1.0, confidence + :boost), updatedAt = :at WHERE memoryId = :id AND active = 1 AND invalidAt IS NULL")
    suspend fun reinforceSemantic(id: String, boost: Double, at: Long): Int
    @Query("UPDATE airi_semantic_memory SET active = 0, deletedAt = :at, updatedAt = :at WHERE memoryId = :id AND deletedAt IS NULL")
    suspend fun deleteSemantic(id: String, at: Long): Int
    @Query("UPDATE airi_semantic_memory SET lastAccessed = :at, accessCount = accessCount + 1 WHERE memoryId IN (:ids)")
    suspend fun touchSemantic(ids: List<String>, at: Long)
    @Query("SELECT * FROM airi_semantic_memory WHERE deletedAt IS NULL AND (embeddingModel != :model OR embeddingVersion != :version OR embeddingDimensions != :dimensions) ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun staleSemanticEmbeddings(model: String, version: Int, dimensions: Int, limit: Int): List<SemanticMemoryEntity>
    @Query("UPDATE airi_semantic_memory SET embedding = :embedding, embeddingModel = :model, embeddingVersion = :version, embeddingDimensions = :dimensions WHERE memoryId = :id")
    suspend fun updateSemanticEmbedding(id: String, embedding: String, model: String, version: Int, dimensions: Int): Int

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
    @Query("SELECT * FROM airi_relationships WHERE targetEntityId = :entityId AND active = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun activeRelationshipForEntity(entityId: String): RelationshipEntity?
    @Query("UPDATE airi_relationships SET active = 0, supersededById = :replacementId, updatedAt = :at WHERE targetEntityId = :entityId AND active = 1")
    suspend fun supersedeRelationships(entityId: String, replacementId: String, at: Long): Int
    @Query("UPDATE airi_relationships SET active = 0, deletedAt = :at, updatedAt = :at WHERE targetEntityId = :entityId AND relationshipType = :type AND active = 1")
    suspend fun endRelationship(entityId: String, type: String, at: Long): Int
    @Query("UPDATE airi_relationships SET active = 0, deletedAt = :at, updatedAt = :at WHERE targetEntityId = :entityId AND active = 1 AND deletedAt IS NULL")
    suspend fun endCurrentRelationship(entityId: String, at: Long): Int
    @Query("UPDATE airi_relationships SET lastAccessed = :at, accessCount = accessCount + 1 WHERE relationshipId IN (:ids)")
    suspend fun touchRelationships(ids: List<String>, at: Long)

    @Query("SELECT * FROM airi_episodes WHERE deletedAt IS NULL ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recentEpisodes(limit: Int): List<EpisodicMemoryEntity>
    @Query("SELECT * FROM airi_episodes WHERE episodeId = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun episodeById(id: String): EpisodicMemoryEntity?
    @Query("SELECT e.* FROM airi_episodes e JOIN airi_episode_fts f ON e.episodeId = f.episodeId WHERE airi_episode_fts MATCH :query AND e.deletedAt IS NULL LIMIT :limit")
    suspend fun searchEpisodeFts(query: String, limit: Int): List<EpisodicMemoryEntity>
    @Query("SELECT e.* FROM airi_episodes e JOIN airi_episode_participants p ON e.episodeId = p.episodeId WHERE p.entityId = :entityId AND e.deletedAt IS NULL ORDER BY e.occurredAt DESC LIMIT :limit")
    suspend fun episodesFor(entityId: String, limit: Int): List<EpisodicMemoryEntity>
    @Query("UPDATE airi_episodes SET lastAccessed = :at, accessCount = accessCount + 1 WHERE episodeId IN (:ids)")
    suspend fun touchEpisodes(ids: List<String>, at: Long)
    @Query("SELECT * FROM airi_episodes WHERE deletedAt IS NULL AND (embeddingModel != :model OR embeddingVersion != :version OR embeddingDimensions != :dimensions) ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun staleEpisodeEmbeddings(model: String, version: Int, dimensions: Int, limit: Int): List<EpisodicMemoryEntity>
    @Query("UPDATE airi_episodes SET embedding = :embedding, embeddingModel = :model, embeddingVersion = :version, embeddingDimensions = :dimensions WHERE episodeId = :id")
    suspend fun updateEpisodeEmbedding(id: String, embedding: String, model: String, version: Int, dimensions: Int): Int
    @Query("UPDATE airi_episodes SET deletedAt = :at WHERE episodeId = :id AND deletedAt IS NULL")
    suspend fun deleteEpisode(id: String, at: Long): Int
    @Query("UPDATE airi_episodes SET stability = :stability, difficulty = :difficulty, lastReviewedAt = :reviewedAt WHERE episodeId = :id AND deletedAt IS NULL")
    suspend fun updateEpisodeReview(id: String, stability: Double, difficulty: Double, reviewedAt: Long): Int
    @Query("UPDATE airi_episodes SET consolidatedAt = :at WHERE episodeId = :id AND consolidatedAt IS NULL")
    suspend fun markEpisodeConsolidated(id: String, at: Long): Int
    @Query("SELECT * FROM airi_episodes WHERE consolidatedAt IS NULL AND deletedAt IS NULL ORDER BY createdAt LIMIT :limit")
    suspend fun unconsolidatedEpisodes(limit: Int): List<EpisodicMemoryEntity>

    @Query("SELECT * FROM airi_goals WHERE deletedAt IS NULL AND status NOT IN ('COMPLETED','ABANDONED') ORDER BY priority DESC, updatedAt DESC LIMIT :limit")
    suspend fun activeGoals(limit: Int): List<GoalMemoryEntity>
    @Query("SELECT * FROM airi_goals WHERE stableKey = :key AND deletedAt IS NULL LIMIT 1")
    suspend fun goalByKey(key: String): GoalMemoryEntity?
    @Query("UPDATE airi_goals SET status = :status, updatedAt = :at WHERE stableKey = :key AND semanticMemoryId = :semanticMemoryId AND deletedAt IS NULL")
    suspend fun closeLinkedGoal(key: String, semanticMemoryId: String, status: String, at: Long): Int
    @Query("UPDATE airi_goals SET lastAccessed = :at, accessCount = accessCount + 1 WHERE goalId IN (:ids)")
    suspend fun touchGoals(ids: List<String>, at: Long)
    @Query("UPDATE airi_goals SET deletedAt = :at, updatedAt = :at WHERE goalId = :id AND deletedAt IS NULL")
    suspend fun deleteGoal(id: String, at: Long): Int
    @Query("SELECT * FROM airi_behavior_patterns WHERE stableKey = :key LIMIT 1")
    suspend fun behavior(key: String): BehaviorObservationEntity?
    @Query("SELECT * FROM airi_behavior_patterns WHERE kind = :kind ORDER BY observationCount DESC LIMIT :limit")
    suspend fun behaviorByKind(kind: String, limit: Int): List<BehaviorObservationEntity>
    @Query("DELETE FROM airi_behavior_patterns WHERE stableKey = :key") suspend fun deleteBehavior(key: String): Int

    @Query("SELECT * FROM airi_conversation_truth WHERE sessionId = :sessionId ORDER BY sequence DESC LIMIT :limit")
    suspend fun recentConversation(sessionId: String, limit: Int): List<ConversationTruthEntity>
    @Query("SELECT COUNT(*) FROM airi_conversation_truth WHERE sessionId = :sessionId") suspend fun conversationCount(sessionId: String): Int
    @Query("SELECT MAX(sequence) FROM airi_conversation_truth WHERE sessionId = :sessionId") suspend fun lastConversationSequence(sessionId: String): Long?
    @Query("SELECT * FROM airi_conversation_truth WHERE sessionId = :sessionId AND sequence BETWEEN :start AND :end ORDER BY sequence")
    suspend fun conversationRange(sessionId: String, start: Long, end: Long): List<ConversationTruthEntity>
    @Query("SELECT * FROM airi_segmentation_state WHERE conversationId = :conversationId LIMIT 1")
    suspend fun segmentationState(conversationId: String): SegmentationStateEntity?
    @Query("SELECT * FROM airi_segmentation_state WHERE conversationStatus != 'CLOSED' AND lastActivityAt <= :before ORDER BY lastActivityAt LIMIT :limit")
    suspend fun abandonedSegmentationStates(before: Long, limit: Int): List<SegmentationStateEntity>
    @Query("SELECT * FROM airi_episode_spans WHERE conversationId = :conversationId ORDER BY startSequence")
    suspend fun episodeSpans(conversationId: String): List<EpisodeSpanEntity>
    @Query("SELECT * FROM airi_pending_review WHERE conversationId = :conversationId ORDER BY createdAt LIMIT :limit")
    suspend fun pendingReviews(conversationId: String, limit: Int): List<PendingReviewEntity>
    @Query("SELECT DISTINCT conversationId FROM airi_pending_review ORDER BY createdAt LIMIT :limit")
    suspend fun pendingReviewConversations(limit: Int): List<String>
    @Query("DELETE FROM airi_pending_review WHERE reviewId IN (:ids)") suspend fun deleteReviews(ids: List<String>): Int
    @Query("SELECT * FROM airi_background_work WHERE state = 'PENDING' AND nextEligibleAt <= :now ORDER BY nextEligibleAt, createdAt LIMIT :limit")
    suspend fun dueBackgroundWork(now: Long, limit: Int): List<MemoryBackgroundWorkEntity>
    @Query("UPDATE airi_background_work SET state = 'RUNNING', updatedAt = :at WHERE workId = :id AND state = 'PENDING' AND nextEligibleAt <= :at")
    suspend fun claimBackgroundWork(id: String, at: Long): Int
    @Query("DELETE FROM airi_background_work WHERE workId = :id") suspend fun completeBackgroundWork(id: String): Int
    @Query("UPDATE airi_background_work SET state = 'PENDING', attemptCount = MIN(8, attemptCount + 1), nextEligibleAt = :nextAt, lastFailure = :failure, updatedAt = :at WHERE workId = :id")
    suspend fun retryBackgroundWork(id: String, nextAt: Long, failure: String, at: Long): Int
    @Query("SELECT MIN(nextEligibleAt) FROM airi_background_work WHERE state = 'PENDING'")
    suspend fun earliestPendingBackgroundWorkAt(): Long?

    @Query("DELETE FROM airi_semantic_memory") suspend fun clearSemantic()
    @Query("DELETE FROM airi_people") suspend fun clearPeople()
    @Query("DELETE FROM airi_episodes") suspend fun clearEpisodes()
    @Query("DELETE FROM airi_goals") suspend fun clearGoals()
    @Query("DELETE FROM airi_behavior_patterns") suspend fun clearBehavior()
    @Query("DELETE FROM airi_conversation_truth") suspend fun clearConversation()
    @Query("DELETE FROM airi_segmentation_state") suspend fun clearSegmentationState()
    @Query("DELETE FROM airi_episode_spans") suspend fun clearEpisodeSpans()
    @Query("DELETE FROM airi_pending_review") suspend fun clearReviewQueue()
    @Query("DELETE FROM airi_semantic_provenance") suspend fun clearSemanticProvenance()
    @Query("DELETE FROM airi_consolidation_actions") suspend fun clearConsolidationActions()
    @Query("DELETE FROM airi_semantic_fts") suspend fun clearSemanticFts()
    @Query("DELETE FROM airi_episode_fts") suspend fun clearEpisodeFts()
    @Query("DELETE FROM airi_spark_trace") suspend fun clearSparkTrace()
    @Query("DELETE FROM airi_background_work") suspend fun clearBackgroundWork()

    @Transaction suspend fun clearAllMemory() {
        clearSemanticFts(); clearEpisodeFts(); clearSemanticProvenance(); clearConsolidationActions(); clearReviewQueue(); clearEpisodeSpans()
        clearSegmentationState(); clearSemantic(); clearPeople(); clearEpisodes(); clearGoals(); clearBehavior(); clearBackgroundWork()
        clearConversation(); clearSparkTrace()
    }
}
