package com.myra.assistant.data.memory

import java.util.UUID

class InMemoryAiriMemoryStore : AiriMemoryStore {
    val people = linkedMapOf<String, PersonEntity>()
    val aliases = linkedMapOf<String, MutableSet<String>>()
    val relationships = mutableListOf<RelationshipEntity>()
    val semantic = mutableListOf<SemanticMemoryEntity>()
    val episodes = mutableListOf<Pair<EpisodicMemoryEntity, List<String>>>()
    val goals = linkedMapOf<String, GoalMemoryEntity>()
    val behavior = linkedMapOf<String, BehaviorObservationEntity>()
    val conversation = mutableListOf<ConversationTruthEntity>()
    val segmentation = linkedMapOf<String, SegmentationStateEntity>()
    val spans = mutableListOf<EpisodeSpanEntity>()
    private var now = 1_000L
    private fun time() = ++now

    override suspend fun peopleByName(name: String): List<PersonEntity> {
        val n = AiriText.normalizeName(name)
        return people.values.filter { it.active && (it.normalizedName == n || n in aliases[it.entityId].orEmpty()) }
    }
    override suspend fun allPeople(limit: Int) = people.values.filter { it.active }.take(limit)
    override suspend fun ensurePerson(name: String, turnId: Long): PersonEntity {
        peopleByName(name).singleOrNull()?.let { return it }
        val t = time(); val row = PersonEntity(UUID.randomUUID().toString(), AiriText.displayName(name), AiriText.normalizeName(name), t, t, t)
        people[row.entityId] = row; aliases.getOrPut(row.entityId) { linkedSetOf() } += row.normalizedName; return row
    }
    override suspend fun renamePerson(entityId: String, replacement: String, turnId: Long): Boolean {
        val old = people[entityId] ?: return false
        if (peopleByName(replacement).any { it.entityId != entityId }) return false
        aliases.getOrPut(entityId) { linkedSetOf() } += old.normalizedName
        people[entityId] = old.copy(canonicalName = AiriText.displayName(replacement), normalizedName = AiriText.normalizeName(replacement), updatedAt = time())
        return true
    }
    override suspend fun deletePerson(entityId: String): Boolean {
        val old = people[entityId] ?: return false; people[entityId] = old.copy(active = false, deletedAt = time())
        relationships.replaceAll { if (it.targetEntityId == entityId) it.copy(active = false, deletedAt = time()) else it }
        semantic.replaceAll { if (it.subjectEntityId == entityId) it.copy(active = false, deletedAt = time()) else it }
        return true
    }
    override suspend fun addRelationship(entityId: String, type: PersonRelationship, evidence: AuthoritativeMemoryTurnEvidence, confidence: Double): String? {
        relationships.indexOfFirst { it.targetEntityId == entityId && it.active && it.relationshipType == type.name }.takeIf { it >= 0 }?.let { return relationships[it].relationshipId }
        val id = UUID.randomUUID().toString(); val t = time()
        relationships.replaceAll { if (it.targetEntityId == entityId && it.active) it.copy(active = false, supersededById = id) else it }
        relationships += RelationshipEntity(id, entityId, type.name, "CURRENT", confidence, "FINAL_USER_TURN", evidence.turnId, evidence.utteranceId, createdAt = t, updatedAt = t, lastAccessed = t)
        return id
    }
    override suspend fun endRelationship(entityId: String, type: PersonRelationship): Boolean {
        var changed = false; relationships.replaceAll { if (it.targetEntityId == entityId && it.relationshipType == type.name && it.active) { changed = true; it.copy(active = false, deletedAt = time()) } else it }; return changed
    }
    override suspend fun activeRelationships(types: Set<PersonRelationship>, limit: Int): List<MemoryEntity> {
        val allowed = types.map { it.name }.toSet()
        val selected = relationships.filter { it.active && it.relationshipType in allowed }.take(limit)
        val ids = selected.map { it.relationshipId }.toSet(); val at = time()
        relationships.replaceAll { if (it.relationshipId in ids) it.copy(lastAccessed = at, accessCount = it.accessCount + 1) else it }
        return selected.mapNotNull { r -> people[r.targetEntityId]?.takeIf { it.active }?.let { p ->
            MemoryEntity(r.relationshipId, "relationship:${r.relationshipType}", MemoryCategory.PERSON.name,
                "${p.canonicalName} is Zopy's ${r.relationshipType.lowercase().replace('_', ' ')}", r.confidence,
                r.provenance, r.createdAt, r.updatedAt, p.entityId, p.canonicalName, lastRecalledAt = at, kind = "RELATIONSHIP") } }
    }
    override suspend fun addSemantic(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence): String? {
        val fact = frame.fact ?: return null; val key = frame.stableKey ?: return null; val normalizedKey = AiriText.semanticKey(key); val t = time()
        semantic.firstOrNull { it.active && it.semanticKey == normalizedKey && it.normalizedStatement == AiriText.normalize(fact) }?.let { current ->
            semantic.replaceAll { if (it.memoryId == current.memoryId) it.copy(confidence = (it.confidence + .05).coerceAtMost(1.0), updatedAt = t) else it }
            return current.memoryId
        }
        val id = UUID.randomUUID().toString()
        if (frame.temporalScope == MemoryTemporalScope.CURRENT && frame.intent in setOf(MemorySemanticIntent.UPDATE_FACT, MemorySemanticIntent.SUPERSEDE_FACT)) semantic.replaceAll { if (it.semanticKey == normalizedKey && it.active) it.copy(active = false, supersededById = id, invalidAt = t) else it }
        semantic += SemanticMemoryEntity(id, normalizedKey, frame.category?.name ?: "PREFERENCE", fact, AiriText.normalize(fact), frame.resolvedEntityId, frame.temporalScope.name, frame.confidence, 6, true, "FINAL_USER_TURN", evidence.turnId, evidence.utteranceId, createdAt = t, updatedAt = t, lastAccessed = t, conversationId = evidence.sessionId)
        return id
    }
    override suspend fun addEpisode(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence, participantIds: List<String>): String? {
        val p = frame.episode ?: return null; val id = UUID.randomUUID().toString(); val t = time()
        episodes += EpisodicMemoryEntity(id, p.eventType, p.summary, AiriText.normalize(p.summary), frame.temporalScope.name, t, frame.confidence, 5, "FINAL_USER_TURN", evidence.turnId, evidence.utteranceId, t, t) to participantIds
        return id
    }
    override suspend fun invalidateSemantic(key: String): Boolean {
        var changed = false; val t = time(); val normalized = AiriText.semanticKey(key)
        semantic.replaceAll { if (it.semanticKey == normalized && it.active) { changed = true; it.copy(active = false, invalidAt = t, updatedAt = t) } else it }
        return changed
    }
    override suspend fun addGoal(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence): String? {
        val g = frame.goal ?: return null; val key = AiriText.semanticKey(frame.stableKey ?: g.title); val t = time(); val id = goals[key]?.goalId ?: UUID.randomUUID().toString()
        goals[key] = GoalMemoryEntity(id, key, g.title, g.description, g.status, g.priority, g.progress, g.deadline, g.parentGoalId, "GOAL", "FINAL_USER_TURN", evidence.turnId, evidence.utteranceId, goals[key]?.createdAt ?: t, t, t); return id
    }
    override suspend fun retrieve(query: String, type: MemoryRecallType, limit: Int): List<MemoryEntity> {
        val result = when (type) {
            MemoryRecallType.FRIENDS -> activeRelationships(PersonRelationship.entries.toSet(), limit)
            MemoryRecallType.BEST_FRIEND -> activeRelationships(setOf(PersonRelationship.BEST_FRIEND), limit)
            MemoryRecallType.EPISODES -> episodes.takeLast(limit).reversed().map { MemoryEntity(it.first.episodeId, "episode:${it.first.eventType}", "LIFE_EVENT", it.first.summary, it.first.confidence, it.first.provenance, it.first.createdAt, it.first.occurredAt, lastRecalledAt = it.first.lastAccessed, kind = "EPISODE") }
            MemoryRecallType.GOALS -> goals.values.take(limit).map { MemoryEntity(it.goalId, it.stableKey, "GOAL", it.description ?: it.title, 1.0, it.provenance, it.createdAt, it.updatedAt, lastRecalledAt = it.lastAccessed, kind = "GOAL") }
            MemoryRecallType.PREFERENCES -> semantic.filter { it.active && it.category in setOf("PREFERENCE", "COMMUNICATION_STYLE") }.take(limit).map(::semanticCard)
            else -> semantic.filter { it.active }.take(limit).map(::semanticCard)
        }
        val at = time(); val ids = result.map { it.id }.toSet()
        semantic.replaceAll { if (it.memoryId in ids) it.copy(lastAccessed = at, accessCount = it.accessCount + 1) else it }
        episodes.replaceAll { pair -> if (pair.first.episodeId in ids) pair.first.copy(lastAccessed = at, accessCount = pair.first.accessCount + 1) to pair.second else pair }
        goals.replaceAll { _, row -> if (row.goalId in ids) row.copy(lastAccessed = at, accessCount = row.accessCount + 1) else row }
        return result
    }
    private fun semanticCard(it: SemanticMemoryEntity) = MemoryEntity(it.memoryId, it.semanticKey, it.category, it.statement, it.confidence, it.provenance, it.createdAt, it.updatedAt, lastRecalledAt = it.lastAccessed)
    override suspend fun activeCards(limit: Int) = (retrieve("", MemoryRecallType.GENERAL, limit) + activeRelationships(PersonRelationship.entries.toSet(), limit) + retrieve("", MemoryRecallType.EPISODES, limit) + retrieve("", MemoryRecallType.GOALS, limit) + behavior.values.filter { it.state == "ACTIVE" }.map {
        MemoryEntity(it.patternId, it.stableKey, "HABIT", it.label, it.confidence, "BEHAVIOR_PATTERN", it.firstObservedAt, it.lastObservedAt, importance = it.importance, explicit = false, kind = "BEHAVIOR")
    }).take(limit)
    override suspend fun forgetCard(card: MemoryEntity) = when (card.kind) {
        "RELATIONSHIP" -> card.entityId?.let { id -> relationships.firstOrNull { it.relationshipId == card.id }?.let { endRelationship(id, PersonRelationship.valueOf(it.relationshipType)) } } == true
        "PERSON" -> card.entityId?.let { deletePerson(it) } == true
        "EPISODE" -> episodes.removeAll { it.first.episodeId == card.id }
        "GOAL" -> goals.entries.removeAll { it.value.goalId == card.id }
        "BEHAVIOR" -> behavior.remove(card.stableKey) != null
        else -> { var changed = false; semantic.replaceAll { if (it.memoryId == card.id && it.active) { changed = true; it.copy(active = false, deletedAt = time()) } else it }; changed }
    }
    override suspend fun clearAll() { people.clear(); aliases.clear(); relationships.clear(); semantic.clear(); episodes.clear(); goals.clear(); behavior.clear(); conversation.clear(); segmentation.clear(); spans.clear() }
    override suspend fun appendConversation(row: ConversationTruthEntity): Boolean { if (conversation.any { it.messageId == row.messageId }) return false; conversation += row; return true }
    override suspend fun promptProjection(sessionId: String, limit: Int) = conversation.filter { it.sessionId == sessionId }.takeLast(limit)
    override suspend fun conversationCount(sessionId: String) = conversation.count { it.sessionId == sessionId }
    override suspend fun lastConversationSequence(sessionId: String) = conversation.filter { it.sessionId == sessionId }.maxOfOrNull { it.sequence }
    override suspend fun segmentationState(conversationId: String) = segmentation[conversationId]
    override suspend fun saveSegmentationState(row: SegmentationStateEntity) { segmentation[row.conversationId] = row }
    override suspend fun conversationRange(conversationId: String, start: Long, end: Long) = conversation
        .filter { it.sessionId == conversationId && it.sequence in start..end }.sortedBy { it.sequence }
    override suspend fun saveEpisodeSpan(row: EpisodeSpanEntity): Boolean {
        if (spans.any { it.spanId == row.spanId }) return false; spans += row; return true
    }
    override suspend fun episodeSpans(conversationId: String) = spans.filter { it.conversationId == conversationId }
    override suspend fun ensureEpisodeForSpan(span: EpisodeSpanEntity, messages: List<ConversationTruthEntity>): String? {
        if (messages.isEmpty() || span.classification != SegmentClassification.INFORMATIVE.name) return null
        val id = "episode:${span.conversationId}:${span.startSequence}:${span.endSequence}"
        if (episodes.any { it.first.episodeId == id }) return id
        val t = time(); val content = messages.joinToString("\n") { "${it.role}: ${it.content}" }
        episodes += EpisodicMemoryEntity(id, "conversation_segment", messages.first().content.take(96),
            AiriText.normalize(content), MemoryTemporalScope.HISTORICAL.name, t, 1.0, 4,
            "CONVERSATION_SEGMENTATION", messages.last().turnId, messages.last().utteranceId, t, t,
            conversationId = span.conversationId, startSequence = span.startSequence, endSequence = span.endSequence,
            title = messages.first().content.take(96), content = content, classification = span.classification) to emptyList()
        return id
    }
    override suspend fun markEpisodeConsolidated(id: String, at: Long): Boolean {
        var changed = false; episodes.replaceAll { pair ->
            if (pair.first.episodeId == id && pair.first.consolidatedAt == null) { changed = true; pair.first.copy(consolidatedAt = at) to pair.second } else pair
        }; return changed
    }
    override suspend fun reviewEpisodes(conversationId: String, ratings: Map<String, EpisodeReviewRating>, reviewedAt: Long): Int {
        var changed = 0; episodes.replaceAll { pair ->
            val rating = ratings[pair.first.episodeId]
            if (rating != null && !pair.first.isFlashbulb) {
                val next = AiriFsrs.review(FsrsState(pair.first.stability, pair.first.difficulty, pair.first.lastReviewedAt), rating, reviewedAt)
                if (next.lastReviewedAt == reviewedAt) { changed++; pair.first.copy(stability = next.stability, difficulty = next.difficulty, lastReviewedAt = reviewedAt) to pair.second } else pair
            } else pair
        }; return changed
    }
    override suspend fun behavior(key: String) = behavior[key]
    override suspend fun upsertBehavior(row: BehaviorObservationEntity) { behavior[row.stableKey] = row }
    override suspend fun behaviorByKind(kind: String, limit: Int) = behavior.values.filter { it.kind == kind }.sortedByDescending { it.observationCount }.take(limit)
    override suspend fun deleteBehavior(key: String) = behavior.remove(key) != null
}
