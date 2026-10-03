package com.myra.assistant.data.memory

import androidx.room.withTransaction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class VerifiedMemoryTransaction(
    val turnId: Long, val operation: MemorySemanticIntent, val status: MemoryTransactionStatus,
    val recordId: String? = null, val reason: MemoryFailureReason? = null, val at: Long = System.currentTimeMillis()
)

enum class MemoryFailureReason {
    NONE, STALE_TURN, WRONG_TURN, QUESTION_MUTATION, EMPTY_SOURCE_SPAN, SOURCE_SPAN_NOT_FINAL,
    CRITICAL_LITERAL_MISSING, AMBIGUOUS_ENTITY, TARGET_NOT_FOUND, TEMPORARY, PROHIBITED_SECRET,
    LOW_CONFIDENCE, SENSITIVE_CONTENT, UNSUPPORTED_OPERATION, VERIFY_FAILED,
    MISSING_REQUIRED_ENTITY, MISSING_REQUIRED_RELATIONSHIP, MISSING_REQUIRED_REPLACEMENT,
    MISSING_REQUIRED_FACT, MISSING_REQUIRED_GOAL, MISSING_REQUIRED_EPISODE,
    HYPOTHETICAL_NOT_ASSERTED, REPORTED_SPEECH_NOT_USER_FACT
}

interface AiriMemoryStore {
    suspend fun <T> transaction(block: suspend AiriMemoryStore.() -> T): T = block()
    suspend fun peopleByName(name: String): List<PersonEntity>
    suspend fun allPeople(limit: Int = 100): List<PersonEntity>
    suspend fun ensurePerson(name: String, turnId: Long): PersonEntity
    suspend fun renamePerson(entityId: String, replacement: String, turnId: Long): Boolean
    suspend fun deletePerson(entityId: String): Boolean
    suspend fun addRelationship(entityId: String, type: PersonRelationship, evidence: AuthoritativeMemoryTurnEvidence, confidence: Double): String?
    suspend fun endRelationship(entityId: String, type: PersonRelationship): Boolean
    suspend fun currentRelationship(entityId: String): RelationshipEntity? = null
    suspend fun endCurrentRelationship(entityId: String): Boolean = false
    suspend fun activeRelationships(types: Set<PersonRelationship>, limit: Int): List<MemoryEntity>
    suspend fun addSemantic(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence): String?
    suspend fun invalidateSemantic(key: String): Boolean = false
    suspend fun addEpisode(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence, participantIds: List<String>): String?
    suspend fun addGoal(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence,
        semanticMemoryId: String? = null): String?
    suspend fun closeGoal(stableKey: String, semanticMemoryId: String, status: String): Boolean = false
    suspend fun retrieve(query: String, type: MemoryRecallType, limit: Int): List<MemoryEntity>
    suspend fun activeCards(limit: Int = 200): List<MemoryEntity>
    suspend fun forgetCard(card: MemoryEntity): Boolean
    suspend fun clearAll()
    suspend fun appendConversation(row: ConversationTruthEntity): Boolean
    suspend fun promptProjection(sessionId: String, limit: Int): List<ConversationTruthEntity>
    suspend fun conversationCount(sessionId: String): Int
    suspend fun lastConversationSequence(sessionId: String): Long? = null
    suspend fun behavior(key: String): BehaviorObservationEntity?
    suspend fun upsertBehavior(row: BehaviorObservationEntity)
    suspend fun behaviorByKind(kind: String, limit: Int = 100): List<BehaviorObservationEntity>
    suspend fun deleteBehavior(key: String): Boolean
    suspend fun segmentationState(conversationId: String): SegmentationStateEntity? = null
    suspend fun abandonedSegmentationStates(before: Long, limit: Int): List<SegmentationStateEntity> = emptyList()
    suspend fun saveSegmentationState(row: SegmentationStateEntity) = Unit
    suspend fun conversationRange(conversationId: String, start: Long, end: Long): List<ConversationTruthEntity> = emptyList()
    suspend fun saveEpisodeSpan(row: EpisodeSpanEntity): Boolean = false
    suspend fun ensureEpisodeForSpan(span: EpisodeSpanEntity, messages: List<ConversationTruthEntity>): String? = null
    suspend fun linkEpisodeProvenance(episodeId: String, conversationId: String, start: Long, end: Long): Int = 0
    suspend fun recordConsolidationAction(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence, memoryId: String?) = Unit
    suspend fun markEpisodeConsolidated(id: String, at: Long): Boolean = false
    suspend fun episodeSpans(conversationId: String): List<EpisodeSpanEntity> = emptyList()
    suspend fun reviewEpisodes(conversationId: String, ratings: Map<String, EpisodeReviewRating>, reviewedAt: Long): Int = 0
    suspend fun enqueueEpisodeReview(conversationId: String, episodeIds: List<String>, query: String): Boolean = false
    suspend fun appendSparkTrace(row: SparkTraceEntity): Boolean = false
    suspend fun reembedStale(limit: Int): Int = 0
    /** True only when the store can produce a neural embedding snapshot now. */
    suspend fun neuralEmbeddingReady(): Boolean = false
    suspend fun unconsolidatedEpisodes(limit: Int): List<EpisodicMemoryEntity> = emptyList()
    suspend fun episodeMessages(episode: EpisodicMemoryEntity): List<ConversationTruthEntity> = emptyList()
    suspend fun semanticCandidates(conversationId: String, limit: Int): List<SemanticMemoryEntity> = emptyList()
    suspend fun semanticCandidatesForEpisode(conversationId: String, query: String, limit: Int): List<SemanticMemoryEntity> =
        semanticCandidates(conversationId, limit)
    suspend fun semanticById(id: String): SemanticMemoryEntity? = null
    suspend fun nearEquivalentSemantic(statement: String, category: String, limit: Int = 20): SemanticMemoryEntity? = null
    suspend fun reinforceSemantic(id: String, episodeId: String, boost: Double): Boolean = false
    suspend fun linkSemanticProvenance(id: String, episodeId: String): Boolean = false
    suspend fun pendingReviewConversations(limit: Int): List<String> = emptyList()
    suspend fun pendingReviews(conversationId: String, limit: Int): List<PendingReviewEntity> = emptyList()
    suspend fun episodeCards(ids: List<String>): List<MemoryEntity> = emptyList()
    suspend fun deletePendingReviews(ids: List<String>): Int = 0
    suspend fun enqueueBackgroundWork(kind: String, subjectId: String, now: Long = System.currentTimeMillis()): Boolean = false
    suspend fun dueBackgroundWork(now: Long, limit: Int): List<MemoryBackgroundWorkEntity> = emptyList()
    suspend fun claimBackgroundWork(workId: String, now: Long): Boolean = false
    suspend fun completeBackgroundWork(workId: String): Boolean = false
    suspend fun retryBackgroundWork(workId: String, attempt: Int, failure: String, now: Long): Boolean = false
    suspend fun recoverExpiredBackgroundLeases(now: Long, leaseMs: Long): Int = 0
    suspend fun earliestRecoverableBackgroundWorkAt(leaseMs: Long): Long? = null
}

class RoomAiriMemoryStore(
    private val database: LyraMemoryDatabase,
    private val dao: AiriMemoryDao = database.airiMemoryDao(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val embeddingProvider: LocalEmbeddingProvider = FeatureHashEmbeddingProvider
) : AiriMemoryStore {
    override suspend fun <T> transaction(block: suspend AiriMemoryStore.() -> T): T =
        database.withTransaction { block(this@RoomAiriMemoryStore) }
    override suspend fun peopleByName(name: String) = dao.peopleByNormalizedName(AiriText.normalizeName(name))
    override suspend fun allPeople(limit: Int) = dao.activePeople(limit.coerceIn(1, 500))

    override suspend fun ensurePerson(name: String, turnId: Long): PersonEntity {
        val clean = AiriText.displayName(name)
        peopleByName(clean).singleOrNull()?.let { return it }
        val now = clock()
        val row = PersonEntity(UUID.randomUUID().toString(), clean, AiriText.normalizeName(clean), now, now, now)
        dao.upsertPerson(row)
        dao.insertAlias(PersonAliasEntity(UUID.randomUUID().toString(), row.entityId, clean, row.normalizedName, turnId, now))
        return row
    }

    override suspend fun renamePerson(entityId: String, replacement: String, turnId: Long): Boolean {
        val person = dao.personById(entityId) ?: return false
        val clean = AiriText.displayName(replacement)
        if (clean.length !in 2..80) return false
        val collisions = peopleByName(clean).filterNot { it.entityId == entityId }
        if (collisions.isNotEmpty()) return false
        val now = clock()
        return database.withTransaction {
            dao.insertAlias(PersonAliasEntity(UUID.randomUUID().toString(), entityId, person.canonicalName, person.normalizedName, turnId, now))
            dao.renamePerson(entityId, clean, AiriText.normalizeName(clean), now) == 1 &&
                dao.personById(entityId)?.canonicalName == clean
        }
    }

    override suspend fun deletePerson(entityId: String): Boolean {
        val now = clock()
        return database.withTransaction {
            dao.deletePersonRelationships(entityId, now)
            dao.deletePersonSemantic(entityId, now)
            dao.deletePersonEpisodes(entityId, now)
            dao.deletePerson(entityId, now) == 1
        }
    }

    override suspend fun addRelationship(entityId: String, type: PersonRelationship, evidence: AuthoritativeMemoryTurnEvidence, confidence: Double): String? {
        val person = dao.personById(entityId) ?: return null
        val existing = dao.currentRelationship(entityId, type.name)
        if (existing != null) return existing.relationshipId
        val now = clock(); val id = UUID.randomUUID().toString()
        return database.withTransaction {
            // Relationship type is a single current dimension for one person; retain prior relation as history.
            dao.supersedeRelationships(entityId, id, now)
            dao.insertRelationship(RelationshipEntity(id, entityId, type.name, MemoryTemporalScope.CURRENT.name,
                confidence, "FINAL_USER_TURN", evidence.turnId, evidence.utteranceId, createdAt = now,
                updatedAt = now, lastAccessed = now))
            dao.currentRelationship(person.entityId, type.name)?.relationshipId.takeIf { it == id }
        }
    }

    override suspend fun endRelationship(entityId: String, type: PersonRelationship): Boolean =
        dao.endRelationship(entityId, type.name, clock()) > 0
    override suspend fun currentRelationship(entityId: String) = dao.activeRelationshipForEntity(entityId)
    override suspend fun endCurrentRelationship(entityId: String): Boolean =
        dao.endCurrentRelationship(entityId, clock()) == 1

    override suspend fun activeRelationships(types: Set<PersonRelationship>, limit: Int): List<MemoryEntity> {
        val allowed = types.map { it.name }.toSet()
        val rows = dao.activeRelationships(limit.coerceIn(1, 100)).filter { it.relationshipType in allowed }
        val people = dao.activePeople(500).associateBy { it.entityId }
        if (rows.isNotEmpty()) dao.touchRelationships(rows.map { it.relationshipId }, clock())
        return rows.mapNotNull { row -> people[row.targetEntityId]?.let { person -> relationshipCard(row, person) } }
    }

    override suspend fun addSemantic(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence): String? {
        val statement = frame.fact?.trim()?.takeIf { it.length in 3..500 } ?: return null
        val key = frame.stableKey?.let(AiriText::semanticKey)?.takeIf(String::isNotBlank) ?: return null
        val now = clock(); val id = UUID.randomUUID().toString()
        val temporal = frame.temporalScope.takeUnless { it == MemoryTemporalScope.UNSPECIFIED } ?: MemoryTemporalScope.CURRENT
        val current = dao.currentSemantic(key)
        if (current != null && current.normalizedStatement == AiriText.normalize(statement)) {
            dao.reinforceSemantic(current.memoryId, .05, now)
            if (frame.sourceEpisodeIds.isNotEmpty()) dao.insertSemanticProvenance(
                frame.sourceEpisodeIds.distinct().map { SemanticProvenanceEntity(current.memoryId, it) }
            )
            return current.memoryId
        }
        val embeddingResult = embeddingProvider.embedResult(statement)
        val embedding = LocalVectorCodec.encode(embeddingResult.vector)
        val row = SemanticMemoryEntity(id, key, frame.category?.name ?: MemoryCategory.PREFERENCE.name,
            statement, AiriText.normalize(statement), frame.resolvedEntityId, temporal.name, frame.confidence,
            importance = 6, explicit = true, provenance = "FINAL_USER_TURN", sourceTurnId = evidence.turnId,
            sourceUtteranceId = evidence.utteranceId, createdAt = now, updatedAt = now, lastAccessed = now,
            conversationId = evidence.sessionId, validAt = now, embedding = embedding,
            embeddingModel = embeddingResult.modelId, embeddingVersion = embeddingResult.version,
            embeddingDimensions = embeddingResult.dimensions)
        return database.withTransaction {
            if (temporal == MemoryTemporalScope.CURRENT && frame.intent in setOf(
                    MemorySemanticIntent.UPDATE_FACT, MemorySemanticIntent.SUPERSEDE_FACT
                )) dao.supersedeSemantic(key, id, now)
            dao.insertSemantic(row)
            dao.insertSemanticFts(SemanticMemoryFtsEntity(id.stableRowId(), id, statement))
            if (frame.sourceEpisodeIds.isNotEmpty()) dao.insertSemanticProvenance(
                frame.sourceEpisodeIds.distinct().map { SemanticProvenanceEntity(id, it) }
            )
            dao.semanticById(id)?.memoryId.takeIf { it == id }
        }
    }
    override suspend fun invalidateSemantic(key: String) = dao.invalidateSemantic(AiriText.semanticKey(key), clock()) > 0

    override suspend fun addEpisode(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence, participantIds: List<String>): String? {
        val payload = frame.episode ?: return null
        val summary = payload.summary.trim().takeIf { it.length in 3..500 } ?: return null
        val now = clock(); val id = UUID.randomUUID().toString()
        return database.withTransaction {
            val embeddingResult = embeddingProvider.embedResult(summary)
            val embedding = LocalVectorCodec.encode(embeddingResult.vector)
            val surprise = payload.importance.coerceIn(0.0, 1.0)
            val fsrs = AiriFsrs.initial(reviewedAt = now)
            dao.insertEpisode(EpisodicMemoryEntity(id, AiriText.semanticKey(payload.eventType), summary,
                AiriText.normalize(summary), frame.temporalScope.name, now, frame.confidence,
                (payload.importance * 10).toInt().coerceIn(1, 10), "FINAL_USER_TURN", evidence.turnId,
                evidence.utteranceId, now, now, conversationId = evidence.sessionId,
                title = payload.eventType.ifBlank { "Conversation episode" }, content = summary,
                embedding = embedding, stability = fsrs.stability, difficulty = fsrs.difficulty,
                surprise = surprise, lastReviewedAt = fsrs.lastReviewedAt,
                embeddingModel = embeddingResult.modelId, embeddingVersion = embeddingResult.version,
                embeddingDimensions = embeddingResult.dimensions,
                isFlashbulb = FlashbulbPolicy.isFlashbulb(surprise, false)))
            dao.insertEpisodeParticipants(participantIds.distinct().map { EpisodeParticipantEntity(id, it) })
            dao.insertEpisodeFts(EpisodicMemoryFtsEntity(id.stableRowId(), id, "${payload.eventType} $summary"))
            id
        }
    }

    override suspend fun addGoal(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence,
        semanticMemoryId: String?): String? {
        val goal = frame.goal ?: return null
        val title = goal.title.trim().takeIf { it.length in 2..200 } ?: return null
        val key = AiriText.semanticKey(frame.stableKey ?: title); val old = dao.goalByKey(key)
        val now = clock(); val id = old?.goalId ?: UUID.randomUUID().toString()
        dao.upsertGoal(GoalMemoryEntity(id, key, title, goal.description, goal.status, goal.priority.coerceIn(0, 10),
            goal.progress.coerceIn(0, 100), goal.deadline, goal.parentGoalId, MemoryCategory.GOAL.name,
            "FINAL_USER_TURN", evidence.turnId, evidence.utteranceId, old?.createdAt ?: now, now,
            old?.lastAccessed ?: now, old?.accessCount ?: 0,
            semanticMemoryId = semanticMemoryId ?: old?.semanticMemoryId))
        return dao.goalByKey(key)?.goalId.takeIf { it == id }
    }
    override suspend fun closeGoal(stableKey: String, semanticMemoryId: String, status: String): Boolean =
        dao.closeLinkedGoal(AiriText.semanticKey(stableKey), semanticMemoryId, status, clock()) == 1

    override suspend fun retrieve(query: String, type: MemoryRecallType, limit: Int): List<MemoryEntity> {
        val bounded = limit.coerceIn(1, 8); val normalizedQuery = AiriText.normalize(query)
        val result = when (type) {
            MemoryRecallType.FRIENDS -> activeRelationships(PersonRelationship.entries.toSet(), bounded)
            MemoryRecallType.BEST_FRIEND -> activeRelationships(setOf(PersonRelationship.BEST_FRIEND), bounded)
            MemoryRecallType.EPISODES -> dao.recentEpisodes(bounded).map(::episodeCard)
            MemoryRecallType.GOALS -> dao.activeGoals(bounded).map(::goalCard)
            MemoryRecallType.PROJECTS -> semanticCards(bounded * 4).filter { it.category == MemoryCategory.PROJECT.name }.take(bounded)
            MemoryRecallType.PREFERENCES -> semanticCards(bounded * 4).filter {
                it.category in setOf(MemoryCategory.PREFERENCE.name, MemoryCategory.COMMUNICATION_STYLE.name)
            }.take(bounded)
            MemoryRecallType.LAST_TRANSACTION -> emptyList()
            MemoryRecallType.GENERAL -> {
                hybridRetrieve(query, normalizedQuery, bounded)
            }
        }
        touchRetrieved(result)
        return result
    }

    override suspend fun activeCards(limit: Int): List<MemoryEntity> =
        (semanticCards(limit) + activeRelationships(PersonRelationship.entries.toSet(), limit) +
            dao.activeGoals(limit).map(::goalCard) + dao.recentEpisodes(limit)
                .filterNot { it.eventType == "conversation_segment" }.map(::episodeCard) +
            BehaviorObservationKind.entries.flatMap { dao.behaviorByKind(it.name, limit) }
                .filter { it.state == "ACTIVE" }.distinctBy { it.patternId }.map(::behaviorCard))
            .sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun forgetCard(card: MemoryEntity): Boolean = when (card.kind) {
        "RELATIONSHIP" -> card.entityId?.let { id -> PersonRelationship.entries.firstOrNull { it.name == card.stableKey.substringAfterLast(':') }?.let { endRelationship(id, it) } } == true
        "PERSON" -> card.entityId?.let { deletePerson(it) } == true
        "SEMANTIC" -> dao.deleteSemantic(card.id, clock()) == 1
        "EPISODE" -> dao.deleteEpisode(card.id, clock()) == 1
        "GOAL" -> dao.deleteGoal(card.id, clock()) == 1
        "BEHAVIOR" -> dao.deleteBehavior(card.stableKey) == 1
        else -> false
    }
    override suspend fun clearAll() = database.withTransaction { dao.clearAllMemory() }
    override suspend fun appendConversation(row: ConversationTruthEntity) = dao.appendConversation(row) != -1L
    override suspend fun promptProjection(sessionId: String, limit: Int): List<ConversationTruthEntity> {
        val bounded = limit.coerceIn(1, 32)
        return ConversationProjection.compact(sessionId, dao.recentConversation(sessionId, bounded).reversed(),
            dao.conversationCount(sessionId), bounded)
    }
    override suspend fun conversationCount(sessionId: String) = dao.conversationCount(sessionId)
    override suspend fun lastConversationSequence(sessionId: String) = dao.lastConversationSequence(sessionId)
    override suspend fun behavior(key: String) = dao.behavior(key)
    override suspend fun upsertBehavior(row: BehaviorObservationEntity) = dao.upsertBehavior(row)
    override suspend fun behaviorByKind(kind: String, limit: Int) = dao.behaviorByKind(kind, limit)
    override suspend fun deleteBehavior(key: String) = dao.deleteBehavior(key) > 0
    override suspend fun segmentationState(conversationId: String) = dao.segmentationState(conversationId)
    override suspend fun abandonedSegmentationStates(before: Long, limit: Int) =
        dao.abandonedSegmentationStates(before, limit.coerceIn(1, 32))
    override suspend fun saveSegmentationState(row: SegmentationStateEntity) = dao.upsertSegmentationState(row)
    override suspend fun conversationRange(conversationId: String, start: Long, end: Long) =
        dao.conversationRange(conversationId, start, end)
    override suspend fun saveEpisodeSpan(row: EpisodeSpanEntity) = dao.insertEpisodeSpan(row) != -1L
    override suspend fun ensureEpisodeForSpan(span: EpisodeSpanEntity, messages: List<ConversationTruthEntity>): String? {
        if (messages.isEmpty() || span.classification != SegmentClassification.INFORMATIVE.name) return null
        val id = "episode:${span.conversationId}:${span.startSequence}:${span.endSequence}"
        dao.episodeById(id)?.let { return it.episodeId }
        val content = messages.joinToString("\n") { "${it.role}: ${it.content}" }.take(12_000)
        val title = messages.firstOrNull { it.role == "user" }?.content?.take(96)?.ifBlank { "Conversation episode" }
            ?: "Conversation episode"
        val created = messages.minOf { it.committedAt }; val ended = messages.maxOf { it.committedAt }
        val embeddingResult = embeddingProvider.embedResult("$title $content")
        val embedding = LocalVectorCodec.encode(embeddingResult.vector)
        val fsrs = AiriFsrs.initial(reviewedAt = clock())
        return database.withTransaction {
            runCatching {
                dao.insertEpisode(EpisodicMemoryEntity(
                    episodeId = id, eventType = "conversation_segment", summary = title,
                    normalizedSummary = AiriText.normalize("$title $content"), temporalScope = MemoryTemporalScope.HISTORICAL.name,
                    occurredAt = ended, confidence = 1.0, importance = 4, provenance = "CONVERSATION_SEGMENTATION",
                    sourceTurnId = messages.last().turnId, sourceUtteranceId = messages.last().utteranceId,
                    createdAt = created, lastAccessed = created, conversationId = span.conversationId,
                    startSequence = span.startSequence, endSequence = span.endSequence, title = title,
                    content = content, classification = span.classification, embedding = embedding,
                    stability = fsrs.stability, difficulty = fsrs.difficulty,
                    lastReviewedAt = fsrs.lastReviewedAt,
                    embeddingModel = embeddingResult.modelId, embeddingVersion = embeddingResult.version,
                    embeddingDimensions = embeddingResult.dimensions
                ))
                dao.insertEpisodeFts(EpisodicMemoryFtsEntity(id.stableRowId(), id, "$title $content"))
                id
            }.getOrNull()
        }
    }
    override suspend fun linkEpisodeProvenance(
        episodeId: String,
        conversationId: String,
        start: Long,
        end: Long
    ): Int {
        val memoryIds = dao.semanticIdsForConversationRange(conversationId, start, end)
        val rows = memoryIds.distinct().map { SemanticProvenanceEntity(it, episodeId) }
        if (rows.isNotEmpty()) dao.insertSemanticProvenance(rows)
        dao.calibrateActionsForRange(conversationId, start, end, episodeId, clock())
        return rows.size
    }
    override suspend fun recordConsolidationAction(
        frame: MemorySemanticFrame,
        evidence: AuthoritativeMemoryTurnEvidence,
        memoryId: String?
    ) {
        val action = when (frame.intent) {
            MemorySemanticIntent.UPDATE_FACT, MemorySemanticIntent.SUPERSEDE_FACT -> SemanticConsolidationAction.UPDATE
            MemorySemanticIntent.INVALIDATE_FACT -> SemanticConsolidationAction.INVALIDATE
            else -> {
                val existing = memoryId?.let { dao.semanticById(it) }
                if (existing != null && existing.sourceTurnId != evidence.turnId) SemanticConsolidationAction.REINFORCE
                else SemanticConsolidationAction.NEW
            }
        }
        val key = frame.stableKey?.let(AiriText::semanticKey)
        val evidenceHash = MessageDigest.getInstance("SHA-256")
            .digest(frame.sourceSpan.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        dao.upsertConsolidationAction(ConsolidationActionEntity(
            actionId = "${evidence.sessionId}:${evidence.turnId}:${frame.intent}:${key.orEmpty()}",
            conversationId = evidence.sessionId, turnId = evidence.turnId, memoryId = memoryId,
            semanticKey = key, action = action.name, sourceEvidenceHash = evidenceHash,
            createdAt = clock()
        ))
    }
    override suspend fun markEpisodeConsolidated(id: String, at: Long) = dao.markEpisodeConsolidated(id, at) > 0
    override suspend fun episodeSpans(conversationId: String) = dao.episodeSpans(conversationId)
    override suspend fun reviewEpisodes(conversationId: String, ratings: Map<String, EpisodeReviewRating>, reviewedAt: Long): Int {
        var changed = 0
        database.withTransaction {
            ratings.forEach { (id, rating) ->
                val row = dao.episodeById(id) ?: return@forEach
                if (row.isFlashbulb) return@forEach
                val next = AiriFsrs.review(FsrsState(row.stability, row.difficulty, row.lastReviewedAt), rating, reviewedAt)
                if (next.lastReviewedAt == reviewedAt) changed += dao.updateEpisodeReview(id, next.stability, next.difficulty, reviewedAt)
            }
        }
        return changed
    }
    override suspend fun enqueueEpisodeReview(conversationId: String, episodeIds: List<String>, query: String): Boolean {
        if (episodeIds.isEmpty()) return false
        val row = PendingReviewEntity(UUID.randomUUID().toString(), conversationId,
            episodeIds.distinct().take(8).joinToString(","), query.take(500), clock())
        return dao.enqueueReview(row) != -1L
    }
    override suspend fun appendSparkTrace(row: SparkTraceEntity) = dao.appendSparkTrace(row) != -1L
    override suspend fun reembedStale(limit: Int): Int {
        // Never let E5 warm-up relabel or overwrite an already-valid neural
        // row with the temporary hash lane.  Reindex is an E5 upgrade job.
        if (!embeddingProvider.isNeuralReady) return 0
        val bounded = limit.coerceIn(1, 64); var changed = 0
        val target = embeddingProvider.embedResult("reindex target snapshot")
        if (!target.neural) return 0
        database.withTransaction {
            dao.staleSemanticEmbeddings(target.modelId, target.version, target.dimensions, bounded).forEach { row ->
                val result = embeddingProvider.embedResult(row.statement)
                if (!result.neural) return@forEach
                changed += dao.updateSemanticEmbedding(row.memoryId,
                    LocalVectorCodec.encode(result.vector), result.modelId, result.version, result.dimensions)
            }
            dao.staleEpisodeEmbeddings(target.modelId, target.version, target.dimensions, bounded).forEach { row ->
                val result = embeddingProvider.embedResult("${row.title} ${row.content}")
                if (!result.neural) return@forEach
                changed += dao.updateEpisodeEmbedding(row.episodeId,
                    LocalVectorCodec.encode(result.vector), result.modelId, result.version, result.dimensions)
            }
        }
        return changed
    }
    override suspend fun neuralEmbeddingReady(): Boolean = embeddingProvider.isNeuralReady
    override suspend fun unconsolidatedEpisodes(limit: Int) = dao.unconsolidatedEpisodes(limit.coerceIn(1, 32))
    override suspend fun episodeMessages(episode: EpisodicMemoryEntity) =
        dao.conversationRange(episode.conversationId, episode.startSequence, episode.endSequence)
    override suspend fun semanticCandidates(conversationId: String, limit: Int) =
        dao.activeSemanticForConversation(conversationId, limit.coerceIn(1, 20)).ifEmpty { dao.activeSemantic(limit.coerceIn(1, 20)) }
    override suspend fun semanticCandidatesForEpisode(conversationId: String, query: String, limit: Int): List<SemanticMemoryEntity> {
        val bounded = limit.coerceIn(1, 20)
        val pool = dao.activeSemanticForConversation(conversationId, RETRIEVAL_CANDIDATE_LIMIT)
            .ifEmpty { dao.activeSemantic(RETRIEVAL_CANDIDATE_LIMIT) }
        if (pool.isEmpty()) return emptyList()
        val normalized = AiriText.normalize(query)
        val ftsQuery = normalized.split(' ').filter { it.length >= 2 }.take(8)
            .joinToString(" OR ") { "\"${it.replace("\"", "")}\"" }
        val allowed = pool.map { it.memoryId }.toSet()
        val lexical = if (ftsQuery.isBlank()) emptyList() else runCatching {
            dao.searchSemanticFts(ftsQuery, RETRIEVAL_CANDIDATE_LIMIT).filter { it.memoryId in allowed }
        }.getOrDefault(emptyList())
        val vector = if (!embeddingProvider.isNeuralReady) emptyList() else {
            val queryEmbedding = embeddingProvider.embedQueryResult(query)
            pool.filter { compatibleEmbedding(it, queryEmbedding) }.sortedByDescending {
                vectorSimilarity(queryEmbedding, it.embedding, it.embeddingModel, it.embeddingVersion, it.embeddingDimensions)
            }
        }
        val fused = ReciprocalRankFusion.merge(listOf(lexical, vector), { it.memoryId }, RETRIEVAL_CANDIDATE_LIMIT)
            .map { it.first }
        val ranked = if (fused.isEmpty()) pool.sortedByDescending { lexicalSemanticScore(normalized, it) } else fused
        val guidelines = ranked.filter { it.category == MemoryCategory.WORKFLOW.name }.take(3)
        val guidelineIds = guidelines.map { it.memoryId }.toSet()
        return (guidelines + ranked.filterNot { it.memoryId in guidelineIds }).distinctBy { it.memoryId }.take(bounded)
    }
    override suspend fun semanticById(id: String) = dao.semanticById(id)
    override suspend fun nearEquivalentSemantic(statement: String, category: String, limit: Int): SemanticMemoryEntity? {
        val normalized = AiriText.normalize(statement)
        val candidates = dao.activeSemanticByCategory(category, limit.coerceIn(1, 20))
        candidates.firstOrNull { it.normalizedStatement == normalized }?.let { return it }
        if (!embeddingProvider.isNeuralReady) return null
        val vector = embeddingProvider.embedResult(statement)
        return candidates.asSequence().map { it to vectorSimilarity(vector, it.embedding, it.embeddingModel,
            it.embeddingVersion, it.embeddingDimensions) }.filter { it.second >= .95 }.maxByOrNull { it.second }?.first
    }
    override suspend fun reinforceSemantic(id: String, episodeId: String, boost: Double): Boolean = database.withTransaction {
        val changed = dao.reinforceSemantic(id, boost.coerceIn(0.0, .1), clock()) == 1
        if (changed) dao.insertSemanticProvenance(listOf(SemanticProvenanceEntity(id, episodeId)))
        changed
    }
    override suspend fun linkSemanticProvenance(id: String, episodeId: String): Boolean {
        dao.insertSemanticProvenance(listOf(SemanticProvenanceEntity(id, episodeId)))
        return true
    }
    override suspend fun pendingReviewConversations(limit: Int) = dao.pendingReviewConversations(limit.coerceIn(1, 16))
    override suspend fun pendingReviews(conversationId: String, limit: Int) = dao.pendingReviews(conversationId, limit.coerceIn(1, 64))
    override suspend fun episodeCards(ids: List<String>) = ids.distinct().take(32).mapNotNull { dao.episodeById(it)?.let(::episodeCard) }
    override suspend fun deletePendingReviews(ids: List<String>) = if (ids.isEmpty()) 0 else dao.deleteReviews(ids)
    override suspend fun enqueueBackgroundWork(kind: String, subjectId: String, now: Long): Boolean {
        val row = MemoryBackgroundWorkEntity("$kind:$subjectId", kind, subjectId, createdAt = now, updatedAt = now)
        return dao.insertBackgroundWork(row) != -1L
    }
    override suspend fun dueBackgroundWork(now: Long, limit: Int) = dao.dueBackgroundWork(now, limit.coerceIn(1, 64))
    override suspend fun claimBackgroundWork(workId: String, now: Long) = dao.claimBackgroundWork(workId, now) == 1
    override suspend fun completeBackgroundWork(workId: String) = dao.completeBackgroundWork(workId) == 1
    override suspend fun retryBackgroundWork(workId: String, attempt: Int, failure: String, now: Long): Boolean {
        val delayMs = (1L shl attempt.coerceIn(0, 8)) * 30_000L
        return dao.retryBackgroundWork(workId, now + delayMs, failure.take(120), now) == 1
    }
    override suspend fun recoverExpiredBackgroundLeases(now: Long, leaseMs: Long) =
        dao.recoverExpiredBackgroundLeases(now - leaseMs.coerceAtLeast(1L), now)
    override suspend fun earliestRecoverableBackgroundWorkAt(leaseMs: Long) =
        dao.earliestRecoverableBackgroundWorkAt(leaseMs.coerceAtLeast(1L))

    private suspend fun semanticCards(limit: Int) = dao.activeSemantic(limit).map { row -> MemoryEntity(
        row.memoryId, row.semanticKey, row.category, row.statement, row.confidence, row.provenance,
        row.createdAt, row.updatedAt, row.subjectEntityId, lastRecalledAt = row.lastAccessed,
        temporalScope = row.temporalScope, importance = row.importance, explicit = row.explicit
    ) }
    private fun relationshipCard(row: RelationshipEntity, person: PersonEntity) = MemoryEntity(
        row.relationshipId, "relationship:${row.relationshipType}", MemoryCategory.PERSON.name,
        "${person.canonicalName} is Zopy's ${row.relationshipType.lowercase().replace('_', ' ')}",
        row.confidence, row.provenance, row.createdAt, row.updatedAt, person.entityId,
        person.canonicalName, row.lastAccessed, row.temporalScope, kind = "RELATIONSHIP"
    )
    private fun episodeCard(row: EpisodicMemoryEntity) = MemoryEntity(row.episodeId, "episode:${row.eventType}",
        MemoryCategory.LIFE_EVENT.name, row.summary, row.confidence, row.provenance, row.createdAt,
        row.occurredAt, lastRecalledAt = row.lastAccessed, temporalScope = row.temporalScope,
        importance = row.importance, kind = "EPISODE")
    private fun goalCard(row: GoalMemoryEntity) = MemoryEntity(row.goalId, row.stableKey, MemoryCategory.GOAL.name,
        row.description ?: row.title, 1.0, row.provenance, row.createdAt, row.updatedAt,
        lastRecalledAt = row.lastAccessed, importance = row.priority, kind = "GOAL")
    private fun behaviorCard(row: BehaviorObservationEntity) = MemoryEntity(row.patternId, row.stableKey,
        MemoryCategory.HABIT.name, row.label, row.confidence, "BEHAVIOR_PATTERN", row.firstObservedAt,
        row.lastObservedAt, importance = row.importance, explicit = false, kind = "BEHAVIOR")
    private suspend fun touchRetrieved(rows: List<MemoryEntity>) {
        if (rows.isEmpty()) return
        val at = clock()
        rows.filter { it.kind == "SEMANTIC" }.map { it.id }.takeIf { it.isNotEmpty() }?.let { dao.touchSemantic(it, at) }
        rows.filter { it.kind == "EPISODE" }.map { it.id }.takeIf { it.isNotEmpty() }?.let { dao.touchEpisodes(it, at) }
        rows.filter { it.kind == "GOAL" }.map { it.id }.takeIf { it.isNotEmpty() }?.let { dao.touchGoals(it, at) }
        // Relationship reads are touched by activeRelationships before projection.
    }
    private suspend fun hybridRetrieve(query: String, normalizedQuery: String, limit: Int): List<MemoryEntity> {
        if (normalizedQuery.isBlank()) return semanticCards(limit)
        val fts = normalizedQuery.split(' ').filter { it.length >= 2 }.take(8)
            .joinToString(" OR ") { "\"${it.replace("\"", "") }\"" }
        // Plast-Mem retrieves 100 candidates independently from each lexical
        // and vector leg before RRF. The final public result remains bounded.
        val semanticPool = dao.activeSemantic(RETRIEVAL_CANDIDATE_LIMIT)
        val episodePool = dao.recentEpisodes(RETRIEVAL_CANDIDATE_LIMIT)
        val queryVector = embeddingProvider.embedQueryResult(query)
        val semanticLexical = runCatching { dao.searchSemanticFts(fts, RETRIEVAL_CANDIDATE_LIMIT) }.getOrDefault(emptyList())
        val semanticVector = semanticPool.filter { compatibleEmbedding(it, queryVector) }.sortedByDescending {
            vectorSimilarity(queryVector, it.embedding, it.embeddingModel, it.embeddingVersion, it.embeddingDimensions)
        }.take(RETRIEVAL_CANDIDATE_LIMIT)
        val semantic = ReciprocalRankFusion.merge(listOf(semanticLexical, semanticVector), { it.memoryId }, RETRIEVAL_CANDIDATE_LIMIT)
            .map { semanticCard(it.first) }
        val episodeLexical = runCatching { dao.searchEpisodeFts(fts, RETRIEVAL_CANDIDATE_LIMIT) }.getOrDefault(emptyList())
        val episodeVector = episodePool.filter { compatibleEmbedding(it, queryVector) }.sortedByDescending {
            vectorSimilarity(queryVector, it.embedding, it.embeddingModel, it.embeddingVersion, it.embeddingDimensions)
        }.take(RETRIEVAL_CANDIDATE_LIMIT)
        val now = clock()
        val episodeRanked = ReciprocalRankFusion.merge(listOf(episodeLexical, episodeVector), { it.episodeId }, RETRIEVAL_CANDIDATE_LIMIT)
            .sortedByDescending { (row, score) ->
                val base = AiriFsrs.retrievability(FsrsState(row.stability, row.difficulty, row.lastReviewedAt), now)
                score * FlashbulbPolicy.retrievalMultiplier(base, row.isFlashbulb, row.surprise)
            }
        val episodes = episodeRanked.map { episodeCard(it.first) }
        episodeRanked.map { it.first }.groupBy { it.conversationId }.forEach { (conversationId, rows) ->
            enqueueEpisodeReview(conversationId, rows.map { it.episodeId }, query)
        }
        val structured = semantic + episodes + activeRelationships(PersonRelationship.entries.toSet(), 24) + dao.activeGoals(16).map(::goalCard)
        return structured.distinctBy { it.id }.take(limit)
    }
    private fun semanticCard(row: SemanticMemoryEntity) = MemoryEntity(
        row.memoryId, row.semanticKey, row.category, row.statement, row.confidence, row.provenance,
        row.createdAt, row.updatedAt, row.subjectEntityId, lastRecalledAt = row.lastAccessed,
        temporalScope = row.temporalScope, importance = row.importance, explicit = row.explicit
    )
    private fun lexicalScore(query: String, row: MemoryEntity): Int {
        if (query.isBlank()) return 1
        val tokens = query.split(' ').filter { it.length >= 2 }.toSet()
        val haystack = (AiriText.normalize(row.fact) + " " + AiriText.normalize(row.stableKey)).split(' ').toSet()
        return tokens.count(haystack::contains) * 10 + row.importance
    }
    private fun String.stableRowId(): Int = (hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

    private fun vectorSimilarity(query: EmbeddingResult, encoded: String, model: String, version: Int, dimensions: Int): Double {
        if (!EmbeddingCompatibility.matches(query, model, version, dimensions, encoded)) return 0.0
        return LocalVectorCodec.decode(encoded, dimensions)?.let { FeatureHashEmbeddingProvider.cosine(query.vector, it) } ?: 0.0
    }
    private fun compatibleEmbedding(row: SemanticMemoryEntity, query: EmbeddingResult) = EmbeddingCompatibility.matches(query,
        row.embeddingModel, row.embeddingVersion, row.embeddingDimensions, row.embedding)
    private fun compatibleEmbedding(row: EpisodicMemoryEntity, query: EmbeddingResult) = EmbeddingCompatibility.matches(query,
        row.embeddingModel, row.embeddingVersion, row.embeddingDimensions, row.embedding)
    private fun lexicalSemanticScore(query: String, row: SemanticMemoryEntity): Int {
        val tokens = query.split(' ').filter { it.length >= 2 }.toSet()
        val rowTokens = (row.normalizedStatement + " " + AiriText.normalize(row.semanticKey)).split(' ').toSet()
        return tokens.count(rowTokens::contains) * 10 + row.importance
    }

    companion object { private const val RETRIEVAL_CANDIDATE_LIMIT = 100 }
}

object AiriText {
    fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
    fun normalizeName(value: String) = normalize(value).replace(" ", "")
    fun semanticKey(value: String) = normalize(value).replace(' ', '_').take(96)
    fun displayName(value: String) = value.trim().replace(Regex("\\s+"), " ").split(' ').joinToString(" ") {
        it.lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase)
    }
}

object ConversationProjection {
    fun compact(sessionId: String, recent: List<ConversationTruthEntity>, total: Int, recentLimit: Int): List<ConversationTruthEntity> {
        if (total <= recentLimit) return recent
        val removed = total - recentLimit
        val first = recent.firstOrNull()?.sequence ?: 0L
        val summary = ConversationTruthEntity("projection:$sessionId:$first", sessionId, first - 1,
            0, "projection:$sessionId", "event", "Compacted $removed older messages; authoritative conversation truth remains stored.",
            recent.firstOrNull()?.committedAt ?: 0L, source = "PROMPT_PROJECTION", finalized = true)
        return listOf(summary) + recent
    }
}
