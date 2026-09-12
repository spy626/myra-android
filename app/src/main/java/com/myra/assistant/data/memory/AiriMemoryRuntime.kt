package com.myra.assistant.data.memory

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

enum class ContextMutation { REPLACE_SELF, APPEND_SELF }
data class ContextEntry(val source: String, val value: String, val turnId: Long, val generation: Long,
    val createdAt: Long = System.currentTimeMillis(), val expiresAt: Long? = null)

/** Direct Kotlin port of AIRI's source-owned replace-self/append-self registry. */
class LyraContextRegistry(private val perBucketLimit: Int = 8, private val historyLimit: Int = 400,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val buckets = linkedMapOf<String, MutableList<ContextEntry>>()
    private val history = ArrayDeque<ContextEntry>()
    @Synchronized fun ingest(entry: ContextEntry, mutation: ContextMutation): Boolean {
        if (entry.expiresAt?.let { it <= clock() } == true) return false
        val bucket = buckets.getOrPut(entry.source) { mutableListOf() }
        if (entry.generation < (bucket.maxOfOrNull { it.generation } ?: Long.MIN_VALUE)) return false
        if (mutation == ContextMutation.REPLACE_SELF) bucket.clear()
        bucket += entry
        while (bucket.size > perBucketLimit) bucket.removeAt(0)
        history += entry
        while (history.size > historyLimit) history.removeFirst()
        expire(); return true
    }
    @Synchronized fun bucket(source: String): List<ContextEntry> { expire(); return buckets[source].orEmpty().toList() }
    @Synchronized fun snapshot(): Map<String, List<ContextEntry>> { expire(); return buckets.mapValues { it.value.toList() } }
    @Synchronized fun history(): List<ContextEntry> = history.toList()
    @Synchronized fun clear() { buckets.clear(); history.clear() }
    private fun expire() { val now = clock(); buckets.values.forEach { it.removeAll { e -> e.expiresAt?.let { x -> x <= now } == true } }; buckets.entries.removeAll { it.value.isEmpty() } }
}

enum class TaskMemoryStatus { ACTIVE, BLOCKED, DONE }
data class TaskMemoryArtifact(val label: String, val value: String, val kind: String)
data class WorkingTaskMemory(
    val taskId: String, val goal: String?, val status: TaskMemoryStatus, val currentStep: String?,
    val confirmedFacts: List<String>, val artifacts: List<TaskMemoryArtifact> = emptyList(),
    val blockers: List<String>, val nextStep: String?, val plan: List<String>,
    val workingAssumptions: List<String>, val lastFailureReason: String?, val completionCriteria: List<String>,
    val foregroundContext: String?, val lastAction: String?, val expectedResult: String?,
    val verificationState: String?, val sourceTurnId: Long, val contextGeneration: Long,
    val updatedAt: Long = System.currentTimeMillis()
)
sealed class TaskMemoryUpdate { data class Updated(val value: WorkingTaskMemory) : TaskMemoryUpdate(); data object IgnoredStale : TaskMemoryUpdate(); data object IgnoredEmpty : TaskMemoryUpdate() }

/** AIRI task-memory bounds, convergence, and stale completed-turn protection. */
class WorkingTaskMemoryStore {
    @Volatile private var current: WorkingTaskMemory? = null
    @Synchronized fun update(value: WorkingTaskMemory): TaskMemoryUpdate {
        val prior = current
        if (prior != null && (value.contextGeneration < prior.contextGeneration || value.sourceTurnId < prior.sourceTurnId)) return TaskMemoryUpdate.IgnoredStale
        val bounded = value.copy(confirmedFacts = value.confirmedFacts.dedupeLast(10), artifacts = value.artifacts.distinctBy { it.kind to it.value }.takeLast(8),
            blockers = value.blockers.dedupeLast(5), plan = value.plan.dedupeLast(6),
            workingAssumptions = value.workingAssumptions.dedupeLast(6), completionCriteria = value.completionCriteria.dedupeLast(6))
        current = if (bounded.status == TaskMemoryStatus.DONE) bounded.copy(blockers = emptyList(), nextStep = null, lastFailureReason = null) else bounded
        return TaskMemoryUpdate.Updated(current!!)
    }
    fun snapshot() = current
    @Synchronized fun clear() { current = null }
    private fun List<String>.dedupeLast(limit: Int) = map(String::trim).filter(String::isNotEmpty).distinct().takeLast(limit)
}

/** Serial ASR buffer; a consumer failure is external and never corrupts the next buffer. */
class FinalTranscriptTurnBuffer(private val maxCharacters: Int = 240) {
    private val fragments = mutableListOf<String>()
    @Synchronized fun append(fragment: String): String? { val clean = fragment.trim(); if (clean.isEmpty()) return null; fragments += clean; return if (fragments.sumOf { it.length + 1 } >= maxCharacters) flush() else null }
    @Synchronized fun flush(): String? = fragments.joinToString(" ").trim().takeIf(String::isNotEmpty).also { fragments.clear() }
    @Synchronized fun clear() = fragments.clear()
}

data class MemoryAuthorization(val authorized: Boolean, val reason: MemoryFailureReason,
    val selectedVariant: String = "NONE", val criticalLiteralsGrounded: Boolean = false)

/** Authorizes source evidence, not translated/model-generated predicates. */
object FinalTurnSourceSpanAuthorizer {
    fun authorize(frame: MemorySemanticFrame, final: AuthoritativeMemoryTurnEvidence, activeTurnId: Long, isQuestion: Boolean): MemoryAuthorization {
        if (frame.sourceTurnId != 0L && frame.sourceTurnId != activeTurnId) return denied(MemoryFailureReason.WRONG_TURN)
        if (!AiriMemoryRuntime.isCurrent(final.sessionId, final.turnId) || final.turnId != activeTurnId) return denied(MemoryFailureReason.STALE_TURN)
        if (isQuestion && frame.intent !in setOf(MemorySemanticIntent.RECALL, MemorySemanticIntent.CLARIFY)) return denied(MemoryFailureReason.QUESTION_MUTATION)
        if (frame.assertionMode == MemoryAssertionMode.QUESTION && frame.intent !in setOf(MemorySemanticIntent.RECALL, MemorySemanticIntent.CLARIFY)) return denied(MemoryFailureReason.QUESTION_MUTATION)
        if (frame.assertionMode == MemoryAssertionMode.HYPOTHETICAL) return denied(MemoryFailureReason.HYPOTHETICAL_NOT_ASSERTED)
        if (frame.assertionMode == MemoryAssertionMode.REPORTED_SPEECH) return denied(MemoryFailureReason.REPORTED_SPEECH_NOT_USER_FACT)
        if (frame.confidence !in .78..1.0) return denied(MemoryFailureReason.LOW_CONFIDENCE)
        AiriMemorySafetyPolicy.rejectReason(frame, final, isQuestion)?.let { return denied(it) }
        if (frame.intent == MemorySemanticIntent.TRANSIENT_CONTEXT || frame.temporalScope == MemoryTemporalScope.TEMPORARY) return MemoryAuthorization(true, MemoryFailureReason.TEMPORARY, criticalLiteralsGrounded = true)
        val span = normalize(frame.sourceSpan)
        if (span.isBlank()) return denied(MemoryFailureReason.EMPTY_SOURCE_SPAN)
        val selected = final.variants.indexOfFirst { containsSpan(normalize(it), span) }
        if (selected < 0) return denied(MemoryFailureReason.SOURCE_SPAN_NOT_FINAL)
        val literals = listOfNotNull(frame.person, frame.replacementPerson) + frame.criticalLiterals
        if (!literals.distinct().all { groundedLiteral(it, final) }) return denied(MemoryFailureReason.CRITICAL_LITERAL_MISSING)
        return MemoryAuthorization(true, MemoryFailureReason.NONE, if (selected == 0) "CANONICAL" else "DISPLAY", true)
    }
    private fun denied(reason: MemoryFailureReason) = MemoryAuthorization(false, reason)
    private fun containsSpan(final: String, span: String): Boolean {
        if (final == span || final.contains(span)) return true
        val wanted = span.split(' '); val actual = final.split(' ')
        if (wanted.size !in 1..12) return false
        var cursor = 0; var gaps = 0
        for ((position, token) in wanted.withIndex()) {
            val end = if (position == 0) actual.size else minOf(actual.size, cursor + 4)
            val found = (cursor until end).firstOrNull { lexicalEquivalent(token, actual[it]) } ?: return false
            gaps += found - cursor; if (gaps > 3) return false; cursor = found + 1
        }
        return true
    }
    internal fun groundedLiteral(literal: String, final: AuthoritativeMemoryTurnEvidence): Boolean {
        val normalized = normalize(literal)
        if (normalized.isBlank()) return false
        if (normalized.any(Char::isDigit)) return final.variants.any { normalize(it).split(' ').contains(normalized) }
        val candidates = final.protectedCanonicalNames + final.protectedDisplayNames + final.variants.flatMap { normalize(it).split(' ').windowed(1, 1) { w -> w.joinToString("") } + normalize(it).split(' ').windowed(2, 1) { w -> w.joinToString("") } + normalize(it).split(' ').windowed(3, 1) { w -> w.joinToString("") } }
        return candidates.any { lexicalEquivalent(normalized.replace(" ", ""), normalize(it).replace(" ", "")) }
    }
    private fun lexicalEquivalent(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.any(Char::isDigit) || b.any(Char::isDigit) || minOf(a.length, b.length) < 4) return false
        val left = phonetic(a); val right = phonetic(b); val distance = editDistance(left, right)
        return distance <= if (maxOf(left.length, right.length) >= 8) 2 else 1
    }
    private fun phonetic(v: String) = normalize(v).replace("ph", "f").replace("v", "w").replace(Regex("([aeiou])$"), "").replace(" ", "")
    private fun normalize(v: String) = Normalizer.normalize(v.lowercase(Locale.ROOT), Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun editDistance(a: String, b: String): Int { var prev = IntArray(b.length + 1) { it }; for (i in a.indices) { val cur = IntArray(b.length + 1); cur[0] = i + 1; for (j in b.indices) cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + if (a[i] == b[j]) 0 else 1); prev = cur }; return prev[b.length] }
}

/** Central AIRI-owner safety policy. Read-only questions are permitted but never persisted. */
object AiriMemorySafetyPolicy {
    private val credential = Regex("\\b(otp|one[ -]?time password|password|passcode|pin|cvv|security code|verification code|recovery code|auth(?:entication)? token|api key|private key|seed phrase)\\b", RegexOption.IGNORE_CASE)
    private val financialId = Regex("\\b(?:bank account|account|card)\\s*(?:number|no|id|#)\\b|\\b(?:account|card)\\b.{0,20}\\d{4}", RegexOption.IGNORE_CASE)
    private val governmentId = Regex("\\b(?:aadhaar|aadhar|pan|passport)\\s*(?:number|no|id|#)\\b|\\b(?:aadhaar|aadhar|pan|passport)\\b.{0,16}[a-z0-9-]*\\d[a-z0-9-]*", RegexOption.IGNORE_CASE)
    private val semanticSecretKeys = Regex("(credential|authentication|otp|password|passcode|private_key|api_key|seed_phrase|bank_account|card_number|government_id|aadhaar|aadhar|pan_number|passport_number)", RegexOption.IGNORE_CASE)

    fun rejectReason(frame: MemorySemanticFrame, final: AuthoritativeMemoryTurnEvidence, isQuestion: Boolean): MemoryFailureReason? {
        if (isQuestion || frame.intent == MemorySemanticIntent.RECALL) return null
        val structured = listOfNotNull(frame.stableKey, frame.category?.name, frame.fact, frame.sourceSpan) +
            frame.criticalLiterals + final.variants
        val combined = structured.joinToString(" ")
        if (semanticSecretKeys.containsMatchIn(listOfNotNull(frame.stableKey, frame.category?.name, frame.fact).joinToString(" ")) ||
            credential.containsMatchIn(combined)) return MemoryFailureReason.PROHIBITED_SECRET
        if (financialId.containsMatchIn(combined) || governmentId.containsMatchIn(combined)) return MemoryFailureReason.SENSITIVE_CONTENT
        return null
    }
}

data class MemoryContractResult(val frame: MemorySemanticFrame?, val reason: MemoryFailureReason? = null,
    val recoveredEntity: Boolean = false)

/** Required payload contract plus conservative, current-turn-only recovery for one missing person. */
object MemoryOperationContractValidator {
    fun validateAndRecover(input: MemorySemanticFrame, final: AuthoritativeMemoryTurnEvidence): MemoryContractResult {
        var frame = when (input.intent) {
            MemorySemanticIntent.ADD_IDEA -> input.copy(category = MemoryCategory.IDEA)
            MemorySemanticIntent.ADD_PROJECT -> input.copy(category = MemoryCategory.PROJECT)
            MemorySemanticIntent.ADD_SOLUTION -> input.copy(category = MemoryCategory.SOLUTION)
            MemorySemanticIntent.ADD_WORKFLOW -> input.copy(category = MemoryCategory.WORKFLOW)
            else -> input
        }
        if (frame.intent in setOf(MemorySemanticIntent.ADD_RELATIONSHIP, MemorySemanticIntent.REMOVE_RELATIONSHIP,
                MemorySemanticIntent.REPLACE_RELATIONSHIP, MemorySemanticIntent.RENAME_ENTITY,
                MemorySemanticIntent.DELETE_ENTITY, MemorySemanticIntent.ADD_LINKED_FACT) && frame.person.isNullOrBlank()) {
            val candidates = currentTurnPeople(frame, final)
            if (candidates.size != 1) return MemoryContractResult(null,
                if (candidates.isEmpty()) MemoryFailureReason.MISSING_REQUIRED_ENTITY else MemoryFailureReason.AMBIGUOUS_ENTITY)
            frame = frame.copy(person = candidates.single(), criticalLiterals = (frame.criticalLiterals + candidates.single()).distinct())
        }
        val initialEpisode = frame.episode
        if (frame.intent == MemorySemanticIntent.ADD_EPISODE && initialEpisode != null &&
            initialEpisode.participants.isEmpty()) {
            val participants = currentTurnPeople(frame, final)
            if (participants.isNotEmpty()) frame = frame.copy(episode = initialEpisode.copy(participants = participants))
        }
        val reason = when (frame.intent) {
            MemorySemanticIntent.ADD_RELATIONSHIP, MemorySemanticIntent.REMOVE_RELATIONSHIP -> when {
                frame.person.isNullOrBlank() -> MemoryFailureReason.MISSING_REQUIRED_ENTITY
                frame.relationship == null -> MemoryFailureReason.MISSING_REQUIRED_RELATIONSHIP
                else -> null
            }
            MemorySemanticIntent.REPLACE_RELATIONSHIP -> when {
                frame.person.isNullOrBlank() -> MemoryFailureReason.MISSING_REQUIRED_ENTITY
                frame.replacementRelationship == null -> MemoryFailureReason.MISSING_REQUIRED_RELATIONSHIP
                else -> null
            }
            MemorySemanticIntent.RENAME_ENTITY -> when {
                frame.person.isNullOrBlank() -> MemoryFailureReason.MISSING_REQUIRED_ENTITY
                frame.replacementPerson.isNullOrBlank() -> MemoryFailureReason.MISSING_REQUIRED_REPLACEMENT
                else -> null
            }
            MemorySemanticIntent.DELETE_ENTITY -> MemoryFailureReason.MISSING_REQUIRED_ENTITY.takeIf { frame.person.isNullOrBlank() }
            MemorySemanticIntent.ADD_LINKED_FACT -> when {
                frame.person.isNullOrBlank() -> MemoryFailureReason.MISSING_REQUIRED_ENTITY
                frame.fact.isNullOrBlank() -> MemoryFailureReason.MISSING_REQUIRED_FACT
                else -> null
            }
            MemorySemanticIntent.ADD_FACT, MemorySemanticIntent.UPDATE_FACT, MemorySemanticIntent.SUPERSEDE_FACT,
            MemorySemanticIntent.ADD_IDEA, MemorySemanticIntent.ADD_PROJECT,
            MemorySemanticIntent.ADD_SOLUTION, MemorySemanticIntent.ADD_WORKFLOW ->
                MemoryFailureReason.MISSING_REQUIRED_FACT.takeIf { frame.fact.isNullOrBlank() || frame.stableKey.isNullOrBlank() }
            MemorySemanticIntent.INVALIDATE_FACT -> MemoryFailureReason.MISSING_REQUIRED_FACT.takeIf { frame.stableKey.isNullOrBlank() }
            MemorySemanticIntent.ADD_EPISODE -> frame.episode.let { episode ->
                MemoryFailureReason.MISSING_REQUIRED_EPISODE.takeIf {
                    episode == null || episode.summary.isBlank() || episode.eventType.isBlank()
                }
            }
            MemorySemanticIntent.ADD_GOAL, MemorySemanticIntent.UPDATE_GOAL -> MemoryFailureReason.MISSING_REQUIRED_GOAL.takeIf { frame.goal?.title.isNullOrBlank() }
            else -> null
        }
        if (frame.sourceSpan.isBlank()) return MemoryContractResult(null, MemoryFailureReason.EMPTY_SOURCE_SPAN)
        if (frame.confidence <= 0.0) return MemoryContractResult(null, MemoryFailureReason.LOW_CONFIDENCE)
        return MemoryContractResult(frame.takeIf { reason == null }, reason, frame !== input)
    }

    private fun currentTurnPeople(frame: MemorySemanticFrame, final: AuthoritativeMemoryTurnEvidence): List<String> {
        val explicit = (final.protectedCanonicalNames + final.protectedDisplayNames + frame.criticalLiterals)
            .map(String::trim).filter { it.length in 2..80 && it.any(Char::isLetter) && it.none(Char::isDigit) }
            .filter { FinalTurnSourceSpanAuthorizer.groundedLiteral(it, final) }
            .map(AiriText::displayName).distinctBy(AiriText::normalizeName)
        if (explicit.isNotEmpty()) return explicit
        val capitalized = Regex("\\b[\\p{Lu}][\\p{L}]{2,}(?:\\s+[\\p{Lu}][\\p{L}]{2,}){0,2}\\b")
            .findAll(frame.sourceSpan).map { it.value }.filter { candidate ->
                final.variants.any { it.contains(candidate) }
            }.toList()
        return capitalized.map(AiriText::displayName).distinctBy(AiriText::normalizeName)
    }
}

object AiriMemoryRuntime {
    val contexts = LyraContextRegistry()
    val tasks = WorkingTaskMemoryStore()
    private val latest = ConcurrentHashMap<String, Long>()
    private val consolidatedTurns = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    /** Starts a new final-owner lifecycle. Turn counters may restart after service recreation. */
    fun beginSession(sessionId: String, turnId: Long) { latest[sessionId] = turnId }
    fun claimTurn(sessionId: String, turnId: Long) { latest.compute(sessionId) { _, old -> maxOf(old ?: 0L, turnId) } }
    fun isCurrent(sessionId: String, turnId: Long) = sessionId == "compatibility" || latest[sessionId] == turnId
    fun markConsolidated(sessionId: String, turnId: Long) { consolidatedTurns += "$sessionId:$turnId" }
    fun wasConsolidated(sessionId: String, turnId: Long) = "$sessionId:$turnId" in consolidatedTurns
}
