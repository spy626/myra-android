package com.myra.assistant.data.memory

import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class UnifiedMemoryKind { SEMANTIC, EPISODIC, PERSON, RELATIONSHIP, GOAL, PROJECT, IDEA, SOLUTION, WORKFLOW, BEHAVIOR }
enum class ContextMutation { REPLACE_SELF, APPEND_SELF }
enum class MemoryAuthorizationReason {
    AUTHORIZED, STALE_TURN, WRONG_TURN, QUESTION, EMPTY_SOURCE_SPAN, SOURCE_SPAN_NOT_FINAL,
    CRITICAL_LITERAL_MISSING, AMBIGUOUS_ENTITY, TEMPORARY, PROHIBITED_SECRET, LOW_CONFIDENCE,
    UNSUPPORTED_OPERATION
}

data class ContextEntry(
    val source: String,
    val value: String,
    val turnId: Long,
    val generation: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null
)

/** AIRI-inspired source-owned buckets. One source cannot erase another source's context. */
class LyraContextRegistry(
    private val perBucketLimit: Int = 8,
    private val historyLimit: Int = 96,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val buckets = linkedMapOf<String, MutableList<ContextEntry>>()
    private val history = ArrayDeque<ContextEntry>()

    @Synchronized fun ingest(entry: ContextEntry, mutation: ContextMutation): Boolean {
        if (entry.expiresAt != null && entry.expiresAt <= clock()) return false
        val bucket = buckets.getOrPut(entry.source) { mutableListOf() }
        val latestGeneration = bucket.maxOfOrNull { it.generation } ?: Long.MIN_VALUE
        if (entry.generation < latestGeneration) return false
        if (mutation == ContextMutation.REPLACE_SELF) bucket.clear()
        bucket += entry
        while (bucket.size > perBucketLimit) bucket.removeAt(0)
        history += entry
        while (history.size > historyLimit) history.removeFirst()
        expire()
        return true
    }

    @Synchronized fun bucket(source: String): List<ContextEntry> {
        expire()
        return buckets[source].orEmpty().toList()
    }

    @Synchronized fun snapshot(): Map<String, List<ContextEntry>> {
        expire()
        return buckets.mapValues { it.value.toList() }
    }

    @Synchronized fun clear() { buckets.clear(); history.clear() }

    private fun expire() {
        val now = clock()
        buckets.values.forEach { rows -> rows.removeAll { it.expiresAt?.let { expiry -> expiry <= now } == true } }
        buckets.entries.removeAll { it.value.isEmpty() }
    }
}

enum class TaskMemoryStatus { ACTIVE, BLOCKED, DONE }
data class WorkingTaskMemory(
    val taskId: String,
    val goal: String?,
    val status: TaskMemoryStatus,
    val currentStep: String?,
    val confirmedFacts: List<String>,
    val blockers: List<String>,
    val nextStep: String?,
    val plan: List<String>,
    val workingAssumptions: List<String>,
    val lastFailureReason: String?,
    val completionCriteria: List<String>,
    val foregroundContext: String?,
    val lastAction: String?,
    val expectedResult: String?,
    val verificationState: String?,
    val sourceTurnId: Long,
    val contextGeneration: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

sealed class TaskMemoryUpdate {
    data class Updated(val value: WorkingTaskMemory) : TaskMemoryUpdate()
    data object IgnoredStale : TaskMemoryUpdate()
}

/** One bounded task snapshot; late async updates cannot overwrite a newer completed turn. */
class WorkingTaskMemoryStore {
    @Volatile private var current: WorkingTaskMemory? = null
    @Synchronized fun update(value: WorkingTaskMemory): TaskMemoryUpdate {
        val previous = current
        if (previous != null && (value.contextGeneration < previous.contextGeneration ||
                value.sourceTurnId < previous.sourceTurnId)) return TaskMemoryUpdate.IgnoredStale
        current = value.copy(
            confirmedFacts = value.confirmedFacts.takeLast(10), blockers = value.blockers.takeLast(5),
            plan = value.plan.takeLast(6), workingAssumptions = value.workingAssumptions.takeLast(6),
            completionCriteria = value.completionCriteria.takeLast(6)
        )
        return TaskMemoryUpdate.Updated(current!!)
    }
    fun snapshot(): WorkingTaskMemory? = current
    @Synchronized fun clear() { current = null }
}

data class ConversationTruthTurn(
    val turnId: Long,
    val utteranceId: String,
    val userText: String,
    val assistantText: String?,
    val committedAt: Long = System.currentTimeMillis()
)

/** Conversation truth stays intact; prompt projection is separately bounded. */
class ConversationTruthStore(private val projectionLimit: Int = 12) {
    private val committed = mutableListOf<ConversationTruthTurn>()
    @Synchronized fun commit(turn: ConversationTruthTurn) {
        if (committed.none { it.utteranceId == turn.utteranceId }) committed += turn
    }
    @Synchronized fun truth(): List<ConversationTruthTurn> = committed.toList()
    @Synchronized fun promptProjection(): List<ConversationTruthTurn> = committed.takeLast(projectionLimit)
}

/** Serial final-fragment buffer. A failed consumer does not poison the next completed turn. */
class FinalTranscriptTurnBuffer(private val maxCharacters: Int = 240) {
    private val fragments = mutableListOf<String>()
    @Synchronized fun append(fragment: String): String? {
        val clean = fragment.trim()
        if (clean.isEmpty()) return null
        fragments += clean
        return if (fragments.sumOf { it.length + 1 } >= maxCharacters) flush() else null
    }
    @Synchronized fun flush(): String? = fragments.joinToString(" ").trim().takeIf { it.isNotEmpty() }
        .also { fragments.clear() }
    @Synchronized fun clear() = fragments.clear()
}

data class MemoryAuthorization(
    val authorized: Boolean,
    val reason: MemoryAuthorizationReason,
    val selectedVariant: String = "NONE",
    val criticalLiteralsGrounded: Boolean = false
)

/** Verifies current-turn source spans and critical literals, not model paraphrase overlap. */
object FinalTurnSourceSpanAuthorizer {
    private val prohibited = Regex(
        "\\b(?:otp|passwords?|passcode|pin|cvv|security code|verification code|recovery code|authentication token|auth token|api key|private key|seed phrase|account number|card number|aadhaar|aadhar|pan number|passport number)\\b",
        RegexOption.IGNORE_CASE
    )

    fun authorize(
        frame: MemorySemanticFrame,
        final: AuthoritativeMemoryTurnEvidence,
        activeTurnId: Long,
        isQuestion: Boolean
    ): MemoryAuthorization {
        if (frame.sourceTurnId != 0L && frame.sourceTurnId != activeTurnId)
            return MemoryAuthorization(false, MemoryAuthorizationReason.WRONG_TURN)
        if (final.sessionId != "compatibility" && !UnifiedMemoryRuntime.isCurrent(final.sessionId, final.turnId))
            return MemoryAuthorization(false, MemoryAuthorizationReason.STALE_TURN)
        if (final.turnId != 0L && final.turnId != activeTurnId)
            return MemoryAuthorization(false, MemoryAuthorizationReason.STALE_TURN)
        if (isQuestion && frame.intent !in setOf(MemorySemanticIntent.RECALL, MemorySemanticIntent.CLARIFY))
            return MemoryAuthorization(false, MemoryAuthorizationReason.QUESTION)
        if (frame.confidence !in .78..1.0)
            return MemoryAuthorization(false, MemoryAuthorizationReason.LOW_CONFIDENCE)
        if (final.variants.any(prohibited::containsMatchIn) || prohibited.containsMatchIn(frame.sourceSpan))
            return MemoryAuthorization(false, MemoryAuthorizationReason.PROHIBITED_SECRET)
        if (frame.intent == MemorySemanticIntent.TRANSIENT_CONTEXT || frame.temporalScope == MemoryTemporalScope.TEMPORARY)
            return MemoryAuthorization(true, MemoryAuthorizationReason.TEMPORARY, criticalLiteralsGrounded = true)
        val span = normalize(frame.sourceSpan)
        if (span.isBlank()) return MemoryAuthorization(false, MemoryAuthorizationReason.EMPTY_SOURCE_SPAN)
        val selected = final.variants.mapIndexedNotNull { index, variant ->
            index.takeIf { sourceSpanPresent(span, normalize(variant)) }
        }.firstOrNull() ?: return MemoryAuthorization(false, MemoryAuthorizationReason.SOURCE_SPAN_NOT_FINAL)
        val literals = criticalLiterals(frame)
        val grounded = literals.all { literal -> criticalLiteralPresent(literal, final) }
        return if (grounded) MemoryAuthorization(
            true, MemoryAuthorizationReason.AUTHORIZED,
            selectedVariant = if (selected == 0) "CANONICAL" else "DISPLAY",
            criticalLiteralsGrounded = true
        ) else MemoryAuthorization(false, MemoryAuthorizationReason.CRITICAL_LITERAL_MISSING)
    }

    private fun criticalLiterals(frame: MemorySemanticFrame): List<String> = buildList {
        frame.person?.let(::add)
        frame.replacementPerson?.let(::add)
        frame.criticalLiterals.filterTo(this) { it.isNotBlank() }
    }.distinct()

    private fun sourceSpanPresent(span: String, final: String): Boolean {
        if (span == final || final.contains(span)) return true
        val spanTokens = span.split(' ')
        if (spanTokens.size > 3) return false
        val finalTokens = final.split(' ')
        return (1..3).any { size -> finalTokens.windowed(size).any { window ->
            equivalent(window.joinToString(""), spanTokens.joinToString(""))
        } }
    }

    private fun criticalLiteralPresent(literal: String, final: AuthoritativeMemoryTurnEvidence): Boolean {
        if (literal.any(Char::isDigit)) return final.variants.any { normalize(it).split(' ').contains(normalize(literal)) }
        val wanted = phonetic(literal)
        if (wanted.length < 3) return false
        return (final.protectedCanonicalNames + final.protectedDisplayNames).any { equivalent(phonetic(it), wanted) } ||
            final.variants.any { value ->
                val tokens = normalize(value).split(' ')
                (1..minOf(3, tokens.size)).any { size -> tokens.windowed(size).any { equivalent(phonetic(it.joinToString("")), wanted) } }
            }
    }

    private fun equivalent(left: String, right: String): Boolean {
        if (left == right) return true
        if (left.any(Char::isDigit) || right.any(Char::isDigit) || left.length < 4 || right.length < 4) return false
        return BestFriendNameSimilarity.likelySame(left, right)
    }

    private fun phonetic(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}]"), "")
        .replace("ph", "f").removeSuffix("a")

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
}

data class EpisodicMemoryPayload(
    val eventType: String,
    val summary: String,
    val participants: List<String>,
    val importance: Double = .5
)

data class GoalMemoryPayload(
    val title: String,
    val description: String?,
    val status: String = "ACTIVE",
    val priority: Int = 0,
    val progress: Int = 0,
    val deadline: Long? = null,
    val parentGoalId: String? = null
)

/** Single consolidation point converts authorized structured meaning into repository candidates. */
object UnifiedMemoryConsolidator {
    fun candidate(frame: MemorySemanticFrame, turnId: Long): MemoryCandidate? = when (frame.intent) {
        MemorySemanticIntent.ADD_FACT, MemorySemanticIntent.UPDATE_FACT,
        MemorySemanticIntent.SUPERSEDE_FACT, MemorySemanticIntent.ADD_LINKED_FACT -> {
            val fact = frame.fact?.trim()?.takeIf { it.length in 3..200 } ?: return null
            val category = frame.category ?: return null
            val key = frame.stableKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val sensitivity = if (category in setOf(
                    MemoryCategory.PREFERENCE, MemoryCategory.COMMUNICATION_STYLE,
                    MemoryCategory.WORKFLOW, MemoryCategory.APP_USAGE, MemoryCategory.SOLUTION
                )) MemorySensitivity.LOW else MemorySensitivity.PERSONAL
            PreferenceMemoryIdentity.canonicalize(MemoryRelationshipPolicy.canonicalize(MemoryCandidate(
                category, fact, "semantic:${category.name.lowercase(Locale.ROOT)}:${MemorySemanticIdentity.token(key)}",
                sensitivity, frame.confidence, source = "final_turn:$turnId",
                provenance = MemoryProvenance.GEMINI_GROUNDED_PROPOSAL,
                entityId = frame.resolvedEntityId, entityName = frame.person,
                observationMetadata = "kind=${if (frame.intent == MemorySemanticIntent.ADD_LINKED_FACT) "PERSON" else "SEMANTIC"};temporal=${frame.temporalScope}"
            )))
        }
        MemorySemanticIntent.ADD_EPISODE -> frame.episode?.let { episode ->
            MemoryCandidate(
                MemoryCategory.LIFE_EVENT, episode.summary,
                "episode:$turnId:${UUID.nameUUIDFromBytes(episode.summary.toByteArray())}",
                MemorySensitivity.PERSONAL, frame.confidence,
                source = "final_turn:$turnId", provenance = MemoryProvenance.GEMINI_GROUNDED_PROPOSAL,
                entityId = frame.resolvedEntityId, entityName = frame.person,
                observationMetadata = "kind=EPISODIC;event=${MemorySemanticIdentity.token(episode.eventType)};importance=${episode.importance}"
            )
        }
        MemorySemanticIntent.ADD_GOAL -> frame.goal?.let { goal ->
            MemoryCandidate(
                MemoryCategory.GOAL, goal.description ?: goal.title,
                "goal:${MemorySemanticIdentity.token(frame.stableKey ?: goal.title)}",
                MemorySensitivity.PERSONAL, frame.confidence,
                source = "final_turn:$turnId", provenance = MemoryProvenance.GEMINI_GROUNDED_PROPOSAL,
                observationMetadata = "kind=GOAL;status=${goal.status};priority=${goal.priority};progress=${goal.progress}"
            )
        }
        else -> null
    }
}

interface SemanticRetriever {
    suspend fun retrieve(query: String, type: MemoryRecallType, limit: Int = 5): List<MemoryEntity>
}

/** Structured keys/people/categories first; bounded lexical fallback. Embeddings are optional. */
class UnifiedMemoryRetriever(private val repository: MemoryRepository) : SemanticRetriever {
    override suspend fun retrieve(query: String, type: MemoryRecallType, limit: Int): List<MemoryEntity> {
        val bounded = limit.coerceIn(1, 8)
        return when (type) {
            MemoryRecallType.FRIENDS -> repository.relevant(query, bounded, MemoryRecallType.FRIENDS)
            MemoryRecallType.BEST_FRIEND -> repository.relevant(query, bounded, MemoryRecallType.BEST_FRIEND)
            MemoryRecallType.LAST_TRANSACTION -> repository.relevant(MemoryWorkingContext.LAST_TRANSACTION_QUERY, bounded)
            MemoryRecallType.EPISODES -> repository.allActive().filter { it.observationMetadata?.contains("kind=EPISODIC") == true }
                .sortedByDescending { it.updatedAt }.take(bounded)
            MemoryRecallType.GOALS -> repository.allActive().filter { it.category == MemoryCategory.GOAL.name }.take(bounded)
            MemoryRecallType.PROJECTS -> repository.allActive().filter { it.category == MemoryCategory.PROJECT.name }.take(bounded)
            MemoryRecallType.GENERAL -> repository.relevant(query, bounded)
        }
    }
}

object UnifiedMemoryRuntime {
    val contexts = LyraContextRegistry()
    val tasks = WorkingTaskMemoryStore()
    val conversations = ConversationTruthStore()
    private val latestTurnBySession = ConcurrentHashMap<String, Long>()
    @Volatile private var latestTurn: Long = 0L
    fun claimTurn(sessionId: String, turnId: Long) {
        latestTurnBySession.compute(sessionId) { _, old -> maxOf(old ?: 0L, turnId) }
        if (turnId > latestTurn) latestTurn = turnId
    }
    fun isCurrent(sessionId: String, turnId: Long): Boolean = latestTurnBySession[sessionId] == turnId
    fun isLatest(turnId: Long): Boolean = turnId == 0L || latestTurn == 0L || latestTurn == turnId
}
