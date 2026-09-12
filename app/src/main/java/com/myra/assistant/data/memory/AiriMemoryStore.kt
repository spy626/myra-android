package com.myra.assistant.data.memory

import androidx.room.withTransaction
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
    suspend fun peopleByName(name: String): List<PersonEntity>
    suspend fun allPeople(limit: Int = 100): List<PersonEntity>
    suspend fun ensurePerson(name: String, turnId: Long): PersonEntity
    suspend fun renamePerson(entityId: String, replacement: String, turnId: Long): Boolean
    suspend fun deletePerson(entityId: String): Boolean
    suspend fun addRelationship(entityId: String, type: PersonRelationship, evidence: AuthoritativeMemoryTurnEvidence, confidence: Double): String?
    suspend fun endRelationship(entityId: String, type: PersonRelationship): Boolean
    suspend fun activeRelationships(types: Set<PersonRelationship>, limit: Int): List<MemoryEntity>
    suspend fun addSemantic(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence): String?
    suspend fun invalidateSemantic(key: String): Boolean = false
    suspend fun addEpisode(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence, participantIds: List<String>): String?
    suspend fun addGoal(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence): String?
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
    suspend fun saveSegmentationState(row: SegmentationStateEntity) = Unit
    suspend fun conversationRange(conversationId: String, start: Long, end: Long): List<ConversationTruthEntity> = emptyList()
    suspend fun saveEpisodeSpan(row: EpisodeSpanEntity): Boolean = false
    suspend fun ensureEpisodeForSpan(span: EpisodeSpanEntity, messages: List<ConversationTruthEntity>): String? = null
    suspend fun markEpisodeConsolidated(id: String, at: Long): Boolean = false
    suspend fun episodeSpans(conversationId: String): List<EpisodeSpanEntity> = emptyList()
    suspend fun reviewEpisodes(conversationId: String, ratings: Map<String, EpisodeReviewRating>, reviewedAt: Long): Int = 0
    suspend fun enqueueEpisodeReview(conversationId: String, episodeIds: List<String>, query: String): Boolean = false
    suspend fun appendSparkTrace(row: SparkTraceEntity): Boolean = false
}

class RoomAiriMemoryStore(
    private val database: LyraMemoryDatabase,
    private val dao: AiriMemoryDao = database.airiMemoryDao(),
    private val clock: () -> Long = System::currentTimeMillis
) : AiriMemoryStore {
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
        val embedding = FeatureHashEmbeddingProvider.encode(FeatureHashEmbeddingProvider.embed(statement))
        val row = SemanticMemoryEntity(id, key, frame.category?.name ?: MemoryCategory.PREFERENCE.name,
            statement, AiriText.normalize(statement), frame.resolvedEntityId, temporal.name, frame.confidence,
            importance = 6, explicit = true, provenance = "FINAL_USER_TURN", sourceTurnId = evidence.turnId,
            sourceUtteranceId = evidence.utteranceId, createdAt = now, updatedAt = now, lastAccessed = now,
            conversationId = evidence.sessionId, validAt = now, embedding = embedding)
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
            val embedding = FeatureHashEmbeddingProvider.encode(FeatureHashEmbeddingProvider.embed(summary))
            val surprise = payload.importance.coerceIn(0.0, 1.0)
            dao.insertEpisode(EpisodicMemoryEntity(id, AiriText.semanticKey(payload.eventType), summary,
                AiriText.normalize(summary), frame.temporalScope.name, now, frame.confidence,
                (payload.importance * 10).toInt().coerceIn(1, 10), "FINAL_USER_TURN", evidence.turnId,
                evidence.utteranceId, now, now, conversationId = evidence.sessionId,
                title = payload.eventType.ifBlank { "Conversation episode" }, content = summary,
                embedding = embedding, surprise = surprise,
                isFlashbulb = FlashbulbPolicy.isFlashbulb(surprise, false)))
            dao.insertEpisodeParticipants(participantIds.distinct().map { EpisodeParticipantEntity(id, it) })
            dao.insertEpisodeFts(EpisodicMemoryFtsEntity(id.stableRowId(), id, "${payload.eventType} $summary"))
            id
        }
    }

    override suspend fun addGoal(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence): String? {
        val goal = frame.goal ?: return null
        val title = goal.title.trim().takeIf { it.length in 2..200 } ?: return null
        val key = AiriText.semanticKey(frame.stableKey ?: title); val old = dao.goalByKey(key)
        val now = clock(); val id = old?.goalId ?: UUID.randomUUID().toString()
        dao.upsertGoal(GoalMemoryEntity(id, key, title, goal.description, goal.status, goal.priority.coerceIn(0, 10),
            goal.progress.coerceIn(0, 100), goal.deadline, goal.parentGoalId, MemoryCategory.GOAL.name,
            "FINAL_USER_TURN", evidence.turnId, evidence.utteranceId, old?.createdAt ?: now, now,
            old?.lastAccessed ?: now, old?.accessCount ?: 0))
        return dao.goalByKey(key)?.goalId.takeIf { it == id }
    }

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
    override suspend fun promptProjection(sessionId: String, limit: Int) = dao.recentConversation(sessionId, limit.coerceIn(1, 32)).reversed()
    override suspend fun conversationCount(sessionId: String) = dao.conversationCount(sessionId)
    override suspend fun lastConversationSequence(sessionId: String) = dao.lastConversationSequence(sessionId)
    override suspend fun behavior(key: String) = dao.behavior(key)
    override suspend fun upsertBehavior(row: BehaviorObservationEntity) = dao.upsertBehavior(row)
    override suspend fun behaviorByKind(kind: String, limit: Int) = dao.behaviorByKind(kind, limit)
    override suspend fun deleteBehavior(key: String) = dao.deleteBehavior(key) > 0
    override suspend fun segmentationState(conversationId: String) = dao.segmentationState(conversationId)
    override suspend fun saveSegmentationState(row: SegmentationStateEntity) = dao.upsertSegmentationState(row)
    override suspend fun conversationRange(conversationId: String, start: Long, end: Long) =
        dao.conversationRange(conversationId, start, end)
    override suspend fun saveEpisodeSpan(row: EpisodeSpanEntity) = dao.insertEpisodeSpan(row) != -1L
    override suspend fun ensureEpisodeForSpan(span: EpisodeSpanEntity, messages: List<ConversationTruthEntity>): String? {
        if (messages.isEmpty() || span.classification != SegmentClassification.INFORMATIVE.name) return null
        val id = "episode:${span.conversationId}:${span.startSequence}:${span.endSequence}"
        dao.episodeById(id)?.let { return it.episodeId }
        val content = messages.joinToString("\n") { "${it.role}: ${it.content}" }.take(2_000)
        val title = messages.firstOrNull { it.role == "user" }?.content?.take(96)?.ifBlank { "Conversation episode" }
            ?: "Conversation episode"
        val created = messages.minOf { it.committedAt }; val ended = messages.maxOf { it.committedAt }
        val embedding = FeatureHashEmbeddingProvider.encode(FeatureHashEmbeddingProvider.embed("$title $content"))
        return database.withTransaction {
            runCatching {
                dao.insertEpisode(EpisodicMemoryEntity(
                    episodeId = id, eventType = "conversation_segment", summary = title,
                    normalizedSummary = AiriText.normalize("$title $content"), temporalScope = MemoryTemporalScope.HISTORICAL.name,
                    occurredAt = ended, confidence = 1.0, importance = 4, provenance = "CONVERSATION_SEGMENTATION",
                    sourceTurnId = messages.last().turnId, sourceUtteranceId = messages.last().utteranceId,
                    createdAt = created, lastAccessed = created, conversationId = span.conversationId,
                    startSequence = span.startSequence, endSequence = span.endSequence, title = title,
                    content = content, classification = span.classification, embedding = embedding
                ))
                dao.insertEpisodeFts(EpisodicMemoryFtsEntity(id.stableRowId(), id, "$title $content"))
                id
            }.getOrNull()
        }
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
            episodeIds.distinct().take(8).joinToString(","), AiriText.semanticKey(query).hashCode().toString(), clock())
        return dao.enqueueReview(row) != -1L
    }
    override suspend fun appendSparkTrace(row: SparkTraceEntity) = dao.appendSparkTrace(row) != -1L

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
        val semanticPool = dao.activeSemantic(80)
        val episodePool = dao.recentEpisodes(40)
        val queryVector = FeatureHashEmbeddingProvider.embed(query)
        val semanticLexical = runCatching { dao.searchSemanticFts(fts, 24) }.getOrDefault(emptyList())
        val semanticVector = semanticPool.sortedByDescending {
            FeatureHashEmbeddingProvider.decode(it.embedding)?.let { v -> FeatureHashEmbeddingProvider.cosine(queryVector, v) } ?: 0.0
        }.take(24)
        val semantic = ReciprocalRankFusion.merge(listOf(semanticLexical, semanticVector), { it.memoryId }, 24)
            .map { semanticCard(it.first) }
        val episodeLexical = runCatching { dao.searchEpisodeFts(fts, 16) }.getOrDefault(emptyList())
        val episodeVector = episodePool.sortedByDescending {
            FeatureHashEmbeddingProvider.decode(it.embedding)?.let { v -> FeatureHashEmbeddingProvider.cosine(queryVector, v) } ?: 0.0
        }.take(16)
        val now = clock()
        val episodes = ReciprocalRankFusion.merge(listOf(episodeLexical, episodeVector), { it.episodeId }, 16)
            .sortedByDescending { (row, score) ->
                val base = AiriFsrs.retrievability(FsrsState(row.stability, row.difficulty, row.lastReviewedAt), now)
                score * FlashbulbPolicy.retrievalMultiplier(base, row.isFlashbulb, row.surprise)
            }.map { episodeCard(it.first) }
        enqueueEpisodeReview("default", episodes.map { it.id }, query)
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
