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
        return relationships.filter { it.active && it.relationshipType in allowed }.take(limit).mapNotNull { r -> people[r.targetEntityId]?.takeIf { it.active }?.let { p ->
            MemoryEntity(r.relationshipId, "relationship:${r.relationshipType}", MemoryCategory.PERSON.name,
                "${p.canonicalName} is Zopy's ${r.relationshipType.lowercase().replace('_', ' ')}", r.confidence,
                r.provenance, r.createdAt, r.updatedAt, p.entityId, p.canonicalName, kind = "RELATIONSHIP") } }
    }
    override suspend fun addSemantic(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence): String? {
        val fact = frame.fact ?: return null; val key = frame.stableKey ?: return null; val id = UUID.randomUUID().toString(); val t = time()
        if (frame.temporalScope == MemoryTemporalScope.CURRENT) semantic.replaceAll { if (it.semanticKey == AiriText.semanticKey(key) && it.active) it.copy(active = false, supersededById = id) else it }
        semantic += SemanticMemoryEntity(id, AiriText.semanticKey(key), frame.category?.name ?: "PREFERENCE", fact, AiriText.normalize(fact), frame.resolvedEntityId, frame.temporalScope.name, frame.confidence, 6, true, "FINAL_USER_TURN", evidence.turnId, evidence.utteranceId, createdAt = t, updatedAt = t, lastAccessed = t)
        return id
    }
    override suspend fun addEpisode(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence, participantIds: List<String>): String? {
        val p = frame.episode ?: return null; val id = UUID.randomUUID().toString(); val t = time()
        episodes += EpisodicMemoryEntity(id, p.eventType, p.summary, AiriText.normalize(p.summary), frame.temporalScope.name, t, frame.confidence, 5, "FINAL_USER_TURN", evidence.turnId, evidence.utteranceId, t, t) to participantIds
        return id
    }
    override suspend fun addGoal(frame: MemorySemanticFrame, evidence: AuthoritativeMemoryTurnEvidence): String? {
        val g = frame.goal ?: return null; val key = AiriText.semanticKey(frame.stableKey ?: g.title); val t = time(); val id = goals[key]?.goalId ?: UUID.randomUUID().toString()
        goals[key] = GoalMemoryEntity(id, key, g.title, g.description, g.status, g.priority, g.progress, g.deadline, g.parentGoalId, "GOAL", "FINAL_USER_TURN", evidence.turnId, evidence.utteranceId, goals[key]?.createdAt ?: t, t, t); return id
    }
    override suspend fun retrieve(query: String, type: MemoryRecallType, limit: Int): List<MemoryEntity> = when (type) {
        MemoryRecallType.FRIENDS -> activeRelationships(PersonRelationship.entries.toSet(), limit)
        MemoryRecallType.BEST_FRIEND -> activeRelationships(setOf(PersonRelationship.BEST_FRIEND), limit)
        MemoryRecallType.EPISODES -> episodes.takeLast(limit).reversed().map { MemoryEntity(it.first.episodeId, "episode:${it.first.eventType}", "LIFE_EVENT", it.first.summary, it.first.confidence, it.first.provenance, it.first.createdAt, it.first.occurredAt, kind = "EPISODE") }
        MemoryRecallType.GOALS -> goals.values.take(limit).map { MemoryEntity(it.goalId, it.stableKey, "GOAL", it.description ?: it.title, 1.0, it.provenance, it.createdAt, it.updatedAt, kind = "GOAL") }
        else -> semantic.filter { it.active }.take(limit).map { MemoryEntity(it.memoryId, it.semanticKey, it.category, it.statement, it.confidence, it.provenance, it.createdAt, it.updatedAt) }
    }
    override suspend fun activeCards(limit: Int) = (retrieve("", MemoryRecallType.GENERAL, limit) + activeRelationships(PersonRelationship.entries.toSet(), limit) + retrieve("", MemoryRecallType.EPISODES, limit) + retrieve("", MemoryRecallType.GOALS, limit)).take(limit)
    override suspend fun forgetCard(card: MemoryEntity) = when (card.kind) { "RELATIONSHIP" -> card.entityId?.let { id -> relationships.firstOrNull { it.relationshipId == card.id }?.let { endRelationship(id, PersonRelationship.valueOf(it.relationshipType)) } } == true; "PERSON" -> card.entityId?.let { deletePerson(it) } == true; else -> { var changed = false; semantic.replaceAll { if (it.memoryId == card.id && it.active) { changed = true; it.copy(active = false, deletedAt = time()) } else it }; changed } }
    override suspend fun clearAll() { people.clear(); aliases.clear(); relationships.clear(); semantic.clear(); episodes.clear(); goals.clear(); behavior.clear(); conversation.clear() }
    override suspend fun appendConversation(row: ConversationTruthEntity): Boolean { if (conversation.any { it.messageId == row.messageId }) return false; conversation += row; return true }
    override suspend fun promptProjection(sessionId: String, limit: Int) = conversation.filter { it.sessionId == sessionId }.takeLast(limit)
    override suspend fun conversationCount(sessionId: String) = conversation.count { it.sessionId == sessionId }
    override suspend fun behavior(key: String) = behavior[key]
    override suspend fun upsertBehavior(row: BehaviorObservationEntity) { behavior[row.stableKey] = row }
    override suspend fun behaviorByKind(kind: String, limit: Int) = behavior.values.filter { it.kind == kind }.sortedByDescending { it.observationCount }.take(limit)
    override suspend fun deleteBehavior(key: String) = behavior.remove(key) != null
}
