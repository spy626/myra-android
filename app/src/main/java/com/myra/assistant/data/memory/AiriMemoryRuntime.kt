package com.myra.assistant.data.memory

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

/**
 * Authorizes a memory operation for the current turn.
 *
 * Save-like operations are permissive because the durable write path stores the
 * authoritative final user text whenever structured model fields are missing or
 * ungrounded. Destructive entity operations remain strict: a model may not rename,
 * delete, or change a relationship for a person that is not grounded in the current
 * final user turn.
 */
object FinalTurnSourceSpanAuthorizer {
    private val destructiveEntityIntents = setOf(
        MemorySemanticIntent.REMOVE_RELATIONSHIP,
        MemorySemanticIntent.REPLACE_RELATIONSHIP,
        MemorySemanticIntent.RENAME_ENTITY,
        MemorySemanticIntent.DELETE_ENTITY
    )

    fun authorize(frame: MemorySemanticFrame, final: AuthoritativeMemoryTurnEvidence, activeTurnId: Long, isQuestion: Boolean): MemoryAuthorization {
        if (frame.sourceTurnId != 0L && frame.sourceTurnId != activeTurnId) return denied(MemoryFailureReason.WRONG_TURN)
        if (!AiriMemoryRuntime.isCurrent(final.sessionId, final.turnId) || final.turnId != activeTurnId) return denied(MemoryFailureReason.STALE_TURN)
        if (isQuestion && frame.intent !in setOf(MemorySemanticIntent.RECALL, MemorySemanticIntent.CLARIFY)) {
            return denied(MemoryFailureReason.QUESTION_MUTATION)
        }
        AiriMemorySafetyPolicy.rejectReason(frame, final, isQuestion)?.let { return denied(it) }

        if (frame.intent in destructiveEntityIntents) {
            val person = frame.person?.trim().orEmpty()
            if (person.isBlank() || !groundedLiteral(person, final)) {
                return denied(MemoryFailureReason.CRITICAL_LITERAL_MISSING)
            }
            if (frame.intent == MemorySemanticIntent.RENAME_ENTITY) {
                val replacement = frame.replacementPerson?.trim().orEmpty()
                if (replacement.isBlank() || !groundedLiteral(replacement, final)) {
                    return denied(MemoryFailureReason.CRITICAL_LITERAL_MISSING)
                }
            }
        }

        return MemoryAuthorization(true, MemoryFailureReason.NONE, "AUTHORITATIVE_FINAL_TURN", true)
    }

    private fun denied(reason: MemoryFailureReason) = MemoryAuthorization(false, reason)

    internal fun groundedLiteral(literal: String, final: AuthoritativeMemoryTurnEvidence): Boolean {
        val normalized = normalize(literal)
        if (normalized.isBlank()) return false
        if (normalized.any(Char::isDigit)) return final.variants.any { normalize(it).split(' ').contains(normalized) }
        val candidates = final.protectedCanonicalNames + final.protectedDisplayNames + final.variants.flatMap {
            val tokens = normalize(it).split(' ').filter(String::isNotBlank)
            tokens.windowed(1, 1) { w -> w.joinToString("") } +
                tokens.windowed(2, 1) { w -> w.joinToString("") } +
                tokens.windowed(3, 1) { w -> w.joinToString("") }
        }
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

        // Safety decisions use the authoritative final user turn, not ungrounded model
        // fields. A hallucinated model fact must neither be saved nor falsely block a
        // valid user memory.
        val authoritative = final.variants.joinToString(" ")
        if (credential.containsMatchIn(authoritative)) return MemoryFailureReason.PROHIBITED_SECRET
        if (financialId.containsMatchIn(authoritative) || governmentId.containsMatchIn(authoritative)) {
            return MemoryFailureReason.SENSITIVE_CONTENT
        }

        // Keep a narrow semantic-key defense for non-model/manual callers that may
        // intentionally provide a secret-typed key with no transcript text.
        if (authoritative.isBlank() && semanticSecretKeys.containsMatchIn(frame.stableKey.orEmpty())) {
            return MemoryFailureReason.PROHIBITED_SECRET
        }
        return null
    }
}

data class MemoryContractResult(val frame: MemorySemanticFrame?, val reason: MemoryFailureReason? = null,
    val recoveredEntity: Boolean = false)

/**
 * Required payload contract, rewritten to auto-recover missing structured fields
 * instead of rejecting the whole save.
 *
 * ARCHITECTURE NOTE: previously ADD_EPISODE required BOTH `episode.summary` AND
 * `episode.eventType` to be non-blank, and ADD_FACT required a pre-computed
 * `stableKey` — any single missing field silently killed the entire save. That's
 * why simple facts ("X is my best friend") saved fine (Gemini reliably fills those
 * 2 fields) while casual narrative facts ("went to Manali with Kareem") did not
 * (eventType in particular was inconsistently populated by the model). We now fill
 * in sensible defaults instead of rejecting, and only refuse a save when there's
 * truly no usable text at all.
 */
object MemoryOperationContractValidator {
    private val genericPersonWords = setOf(
        "someone", "somebody", "anyone", "anybody", "person", "friend", "friends",
        "dost", "koi", "kisi", "some one", "कोई", "किसी", "दोस्त"
    )

    fun validateAndRecover(input: MemorySemanticFrame, final: AuthoritativeMemoryTurnEvidence): MemoryContractResult {
        var frame = input

        // Best-effort person recovery for relationship/linked-fact/episode operations
        // that are missing an explicit person — helpful, not a hard requirement below.
        if (frame.intent in setOf(MemorySemanticIntent.ADD_RELATIONSHIP, MemorySemanticIntent.REMOVE_RELATIONSHIP,
                MemorySemanticIntent.REPLACE_RELATIONSHIP, MemorySemanticIntent.RENAME_ENTITY,
                MemorySemanticIntent.DELETE_ENTITY, MemorySemanticIntent.ADD_LINKED_FACT) && frame.person.isNullOrBlank()) {
            val candidates = currentTurnPeople(frame, final)
            if (candidates.size == 1) {
                frame = frame.copy(person = candidates.single(), criticalLiterals = (frame.criticalLiterals + candidates.single()).distinct())
            }
            // If we still can't find exactly one person, fall through — most of these
            // intents just fall back to treating it as a plain ADD_FACT below rather
            // than blocking the save outright (see the `reason` block).
        }

        val initialEpisode = frame.episode
        if (frame.intent == MemorySemanticIntent.ADD_EPISODE) {
            val participants = initialEpisode?.participants?.takeIf { it.isNotEmpty() } ?: currentTurnPeople(frame, final)
            val summary = initialEpisode?.summary?.trim()?.takeUnless { it.isBlank() }
                ?: frame.fact?.trim()?.takeUnless { it.isBlank() }
                ?: frame.sourceSpan.trim().takeUnless { it.isBlank() }
                ?: final.sourceText.trim().takeUnless { it.isBlank() }
            val eventType = initialEpisode?.eventType?.trim()?.takeUnless { it.isBlank() } ?: "activity"
            if (summary != null) {
                frame = frame.copy(episode = (initialEpisode ?: EpisodicMemoryPayload("activity", summary, participants))
                    .copy(eventType = eventType, summary = summary, participants = participants))
            }
        }

        // Only genuinely empty content gets rejected now — no more required-field
        // checklists per intent type.
        val hasUsableContent = !frame.fact.isNullOrBlank() ||
            frame.episode?.summary?.isNotBlank() == true ||
            frame.goal?.title?.isNullOrBlank() == false ||
            !frame.sourceSpan.isBlank()

        val reason = when (frame.intent) {
            // Save-like operations are allowed to fall back to the authoritative raw
            // final user text later in mutate(); missing model structure is not a reason
            // to lose the memory.
            MemorySemanticIntent.ADD_RELATIONSHIP,
            MemorySemanticIntent.ADD_LINKED_FACT,
            MemorySemanticIntent.ADD_FACT,
            MemorySemanticIntent.UPDATE_FACT,
            MemorySemanticIntent.SUPERSEDE_FACT,
            MemorySemanticIntent.ADD_EPISODE,
            MemorySemanticIntent.ADD_GOAL ->
                MemoryFailureReason.MISSING_REQUIRED_FACT.takeIf { !hasUsableContent && final.sourceText.isBlank() }

            // Destructive/update-entity operations stay strict because auto-filling a
            // delete/rename target would be unsafe.
            MemorySemanticIntent.REMOVE_RELATIONSHIP,
            MemorySemanticIntent.DELETE_ENTITY ->
                MemoryFailureReason.MISSING_REQUIRED_ENTITY.takeIf { frame.person.isNullOrBlank() }

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

            else -> null
        }

        // Auto-generate a stableKey if the model didn't supply one, rather than
        // rejecting the save for a missing internal bookkeeping field.
        if (reason == null && frame.stableKey.isNullOrBlank()) {
            val basis = frame.fact ?: frame.episode?.summary ?: frame.sourceSpan.takeIf(String::isNotBlank) ?: final.sourceText
            frame = frame.copy(stableKey = "auto:${frame.intent.name.lowercase()}:${AiriText.semanticKey(basis).take(48)}")
        }

        return MemoryContractResult(frame.takeIf { reason == null }, reason, frame !== input)
    }

    private fun currentTurnPeople(frame: MemorySemanticFrame, final: AuthoritativeMemoryTurnEvidence): List<String> {
        fun validPerson(value: String): Boolean {
            val normalized = AiriText.normalizeName(value)
            return value.length in 2..80 && value.any(Char::isLetter) && value.none(Char::isDigit) &&
                normalized.isNotBlank() && normalized !in genericPersonWords
        }

        val explicit = (final.protectedCanonicalNames + final.protectedDisplayNames + frame.criticalLiterals)
            .map(String::trim)
            .filter(::validPerson)
            .filter { FinalTurnSourceSpanAuthorizer.groundedLiteral(it, final) }
            .map(AiriText::displayName)
            .filter(::validPerson)
            .distinctBy(AiriText::normalizeName)
        if (explicit.isNotEmpty()) return explicit

        // Never mine a model-generated sourceSpan for a person. Scan the actual final
        // user transcript so recovery cannot manufacture an entity.
        val capitalized = final.variants
            .flatMap { variant ->
                Regex("\\b[\\p{Lu}][\\p{L}]{2,}(?:\\s+[\\p{Lu}][\\p{L}]{2,}){0,2}\\b")
                    .findAll(variant)
                    .map { it.value }
                    .toList()
            }
            .filter(::validPerson)
            .distinct()
        return capitalized.map(AiriText::displayName).filter(::validPerson).distinctBy(AiriText::normalizeName)
    }
}

object AiriMemoryRuntime {
    val contexts = LyraContextRegistry()
    val tasks = WorkingTaskMemoryStore()
    private val latest = ConcurrentHashMap<String, Long>()
    /** Starts a new final-owner lifecycle. Turn counters may restart after service recreation. */
    fun beginSession(sessionId: String, turnId: Long) { latest[sessionId] = turnId }
    fun claimTurn(sessionId: String, turnId: Long) { latest.compute(sessionId) { _, old -> maxOf(old ?: 0L, turnId) } }
    fun isCurrent(sessionId: String, turnId: Long) = sessionId == "compatibility" || latest[sessionId] == turnId
}
