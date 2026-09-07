package com.myra.assistant.data.memory

import android.util.Log
import java.util.Locale
import java.util.UUID

enum class MemoryDecision { IGNORE, RECALL, SAVE, UPDATE, DELETE, NEEDS_CLARIFICATION, REJECT }
enum class MemoryTransactionStatus { SUCCEEDED, FAILED, REJECTED }

data class LastMemoryTransaction(
    val type: MemoryDecision,
    val targetId: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val status: MemoryTransactionStatus,
    val failureReason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object MemoryWorkingContext {
    const val LAST_TRANSACTION_QUERY = "__last_memory_transaction__"

    @Volatile var lastTransaction: LastMemoryTransaction? = null
        private set
    @Volatile var recentPerson: String? = null
        private set

    fun transaction(value: LastMemoryTransaction) { lastTransaction = value }
    fun person(value: String?) { if (!value.isNullOrBlank()) recentPerson = value.trim() }
    fun clear() { lastTransaction = null; recentPerson = null }

    fun failedTransactionFact(): String? {
        val value = lastTransaction ?: return null
        if (value.status != MemoryTransactionStatus.FAILED) return null
        return when (value.type) {
            MemoryDecision.UPDATE -> when {
                !value.oldValue.isNullOrBlank() && !value.newValue.isNullOrBlank() ->
                    "The last memory update that failed was changing ${value.oldValue} to ${value.newValue}."
                !value.newValue.isNullOrBlank() ->
                    "The last memory update that failed was for ${value.newValue}."
                !value.oldValue.isNullOrBlank() ->
                    "The last memory update that failed was for ${value.oldValue}."
                else -> "The last memory update failed before a target could be verified."
            }
            MemoryDecision.DELETE -> value.oldValue?.takeIf { it.isNotBlank() }
                ?.let { "The last memory deletion that failed was for $it." }
                ?: "The last memory deletion failed because its target could not be verified."
            MemoryDecision.SAVE -> value.newValue?.takeIf { it.isNotBlank() }
                ?.let { "The last memory save that failed was for $it." }
                ?: "The last memory save failed before it could be verified."
            else -> value.failureReason?.takeIf { it.isNotBlank() }
                ?.let { "The last memory operation failed: $it." }
                ?: "The last memory operation failed before it could be verified."
        }
    }
}

object MemorySessionIndex {
    @Volatile var revision: Long = 0L
        private set
    @Volatile private var active: List<MemoryEntity> = emptyList()
    fun publish(rows: List<MemoryEntity>) { active = rows; revision++ }
    fun invalidate() { active = emptyList(); revision++ }
    fun snapshot(): List<MemoryEntity> = active
}

sealed class MemoryBrainOutcome {
    data object Ignored : MemoryBrainOutcome()
    data class Recalled(val rows: List<MemoryEntity>, val workingAnswer: String? = null) : MemoryBrainOutcome()
    data class Mutated(val result: MemoryWriteResult, val explicit: Boolean) : MemoryBrainOutcome()
    data class Deleted(val succeeded: Boolean) : MemoryBrainOutcome()
    data class Rejected(val reason: String) : MemoryBrainOutcome()
}

object NaturalMemoryRequestResolver {
    private val forgetIntent = Regex(
        "\\b(?:forget|delete|remove|bhool\\s*(?:jao|do|dena)?|bhul\\s*(?:jao|do|dena)?|hata\\s*(?:do|dena|o))\\b",
        RegexOption.IGNORE_CASE
    )
    private val protectedNegation = Regex(
        "\\b(?:mat|dont|don't|do\\s+not|nahi|nahin)\\s+(?:bhool|bhul|forget|delete|remove|hata)\\b",
        RegexOption.IGNORE_CASE
    )
    private val pronounReference = Regex(
        "\\b(?:uska|uski|uske|unka|unki|unke|him|her|that\\s+person|that\\s+friend)\\b",
        RegexOption.IGNORE_CASE
    )

    fun hasForgetIntent(text: String): Boolean {
        val clean = normalize(text)
        return forgetIntent.containsMatchIn(clean) && !protectedNegation.containsMatchIn(clean)
    }

    fun resolvePersonTarget(
        text: String,
        memories: List<MemoryEntity>,
        recentPerson: String?
    ): String? {
        if (!hasForgetIntent(text)) return null
        val clean = normalize(text)
        val names = memories.asSequence()
            .mapNotNull { memory ->
                memory.entityName
                    ?: memory.takeIf { it.category == MemoryCategory.PERSON.name }
                        ?.let { MemoryRelationshipPolicy.personName(it.fact) }
            }
            .filter { it.length >= 2 }
            .distinctBy { normalize(it) }
            .sortedByDescending { it.length }
            .toList()
        names.firstOrNull { name -> containsPhrase(clean, normalize(name)) }?.let { return it }
        return recentPerson?.takeIf { pronounReference.containsMatchIn(clean) }
    }

    private fun containsPhrase(text: String, phrase: String): Boolean =
        Regex("(?:^|\\s)${Regex.escape(phrase)}(?:$|\\s)", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

object MemoryIntentClassifier {
    private val interrogative = Regex(
        "\\b(?:kya|kaun|kaunsa|kaunsi|kiska|kis|kab|what|who|which|when|where)\\b",
        RegexOption.IGNORE_CASE
    )
    private val questionStart = Regex(
        "^(?:kya|kaun|kaunsa|kaunsi|kiska|kis|kab|where|what|who|which|when|do you remember|tumhe).+",
        RegexOption.IGNORE_CASE
    )
    private val questionSubject = Regex(
        "\\b(?:yaad|memory|remember|update|delete|naam|name|project|friend|dost|person)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isMemoryQuestion(text: String): Boolean {
        val clean = text.trim()
        return questionSubject.containsMatchIn(clean) &&
            (clean.endsWith("?") || questionStart.containsMatchIn(clean) || interrogative.containsMatchIn(clean))
    }

    fun decision(text: String): MemoryDecision {
        if (isMemoryQuestion(text)) return MemoryDecision.RECALL
        if (NaturalMemoryRequestResolver.hasForgetIntent(text)) return MemoryDecision.DELETE
        return when (MemoryCommandParser.parse(text)) {
            is MemoryCommand.Remember -> MemoryDecision.SAVE
            is MemoryCommand.Read -> MemoryDecision.RECALL
            is MemoryCommand.Forget -> MemoryDecision.DELETE
            null -> if (isEntityCorrection(text)) {
                MemoryDecision.UPDATE
            } else if (NaturalMemoryExtractor.extract(text).isNotEmpty()) MemoryDecision.SAVE else MemoryDecision.IGNORE
        }
    }

    private fun isEntityCorrection(text: String): Boolean {
        val correction = BestFriendNameCorrectionParser.parse(text, MemoryWorkingContext.recentPerson) ?: return false
        val operationWords = Regex(
            "\\b(?:feature|update|memory|delete|failed|fail|paya|payi|hua|hui|problem|system)\\b",
            RegexOption.IGNORE_CASE
        )
        return !operationWords.containsMatchIn(correction.oldName) &&
            !operationWords.containsMatchIn(correction.newName)
    }
}

object NaturalMemoryExtractor {
    private val friend = Regex(
        "(?:mera|meri|my)\\s+(?:friend|dost)\\s+([\\p{L}][\\p{L} .'-]{1,40}?)(?:\\s+hai|$)",
        RegexOption.IGNORE_CASE
    )
    private val travel = Regex(
        "(?:main|i)\\s+([\\p{L}][\\p{L} .'-]{1,40}?)\\s+ke\\s+saath\\s+(.+?)\\s+(?:gaya|gayi|travel(?:led)?)(?:\\s+tha|\\s+thi|$)",
        RegexOption.IGNORE_CASE
    )
    private val dailyHabit = Regex(
        "^(?:main|mai|mein)\\s+(?:roz|har\\s+din)\\s+(.+?)\\s+(dekhta|dekhti|use\\s+karta|use\\s+karti|karta|karti)\\s+(?:hun|hoon|hu)$",
        RegexOption.IGNORE_CASE
    )

    fun extract(text: String): List<MemoryCandidate> {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        val direct = mutableListOf<MemoryCandidate>()
        friend.find(clean)?.let { match ->
            val name = canonicalName(match.groupValues[1])
            if (name.isNotBlank()) direct += MemoryCandidate(
                MemoryCategory.PERSON, "$name is Zopy's friend", "person:${token(name)}:identity",
                MemorySensitivity.PERSONAL, .96, source = "natural_conversation",
                provenance = MemoryProvenance.USER_DIRECT_STATEMENT,
                entityId = stablePersonId(name), entityName = name
            )
        }
        travel.find(clean)?.let { match ->
            val name = canonicalName(match.groupValues[1])
            val event = match.groupValues[2].trim()
            if (name.isNotBlank() && event.length >= 3) direct += MemoryCandidate(
                MemoryCategory.LIFE_EVENT, "Zopy travelled $event with $name",
                "person:${token(name)}:life_event:${token(event).take(48)}", MemorySensitivity.PERSONAL,
                .91, source = "natural_conversation", provenance = MemoryProvenance.USER_DIRECT_STATEMENT,
                entityId = stablePersonId(name), entityName = name
            )
        }
        dailyHabit.matchEntire(clean)?.let { match ->
            val subject = match.groupValues[1].trim()
            val verb = match.groupValues[2].lowercase(Locale.ROOT)
            if (subject.length in 2..80) {
                val action = when {
                    verb.startsWith("dekh") -> "watches"
                    verb.startsWith("use") -> "uses"
                    else -> "does"
                }
                direct += MemoryCandidate(
                    MemoryCategory.HABIT,
                    "Zopy $action $subject every day",
                    "habit:$action:${token(subject).take(48)}",
                    MemorySensitivity.PERSONAL,
                    .92,
                    source = "natural_conversation",
                    provenance = MemoryProvenance.USER_DIRECT_STATEMENT
                )
            }
        }
        if (direct.isNotEmpty()) return direct
        val linked = PersonLinkedMemoryExtractor.extractAll(clean)
        if (linked.isNotEmpty()) return linked.map { it.copy(provenance = MemoryProvenance.USER_DIRECT_STATEMENT) }
        PersonalMemoryExtractor.extract(clean)?.let {
            return listOf(it.copy(provenance = MemoryProvenance.USER_DIRECT_STATEMENT))
        }
        return when (val change = AutomaticMemoryChangeParser.parse(clean)) {
            is AutomaticMemoryChange.Save -> listOf(change.candidate.copy(provenance = MemoryProvenance.USER_DIRECT_STATEMENT))
            else -> emptyList()
        }
    }

    private fun canonicalName(value: String) = value.trim().split(Regex("\\s+")).joinToString(" ") {
        it.lowercase(Locale.ROOT).replaceFirstChar { char -> char.uppercase() }
    }
    private fun token(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
    fun stablePersonId(name: String) = "person-${UUID.nameUUIDFromBytes(token(name).toByteArray())}"
}

class MemoryBrainCoordinator(private val repository: MemoryRepository) {
    suspend fun processGroundedProposal(candidate: MemoryCandidate): MemoryWriteResult {
        log("MEMORY_CANDIDATE category=${candidate.category} key=${candidate.stableKey} source=${candidate.provenance} confidence=${candidate.confidence}")
        val result = repository.saveGrounded(candidate)
        MemoryWorkingContext.transaction(
            LastMemoryTransaction(
                type = MemoryDecision.SAVE,
                newValue = candidate.stableKey,
                status = if (result is MemoryWriteResult.Saved) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.REJECTED,
                failureReason = (result as? MemoryWriteResult.Rejected)?.reason
            )
        )
        log("MEMORY_TRANSACTION decision=SAVE status=${result::class.simpleName}")
        return result
    }

    suspend fun processFinalTurn(text: String, supplemental: List<MemoryCandidate> = emptyList()): MemoryBrainOutcome {
        val classified = MemoryIntentClassifier.decision(text)
        val decision = if (classified == MemoryDecision.IGNORE && supplemental.isNotEmpty()) MemoryDecision.SAVE else classified
        log("MEMORY_DECISION decision=$decision")
        return when (decision) {
            MemoryDecision.IGNORE -> MemoryBrainOutcome.Ignored
            MemoryDecision.RECALL -> {
                val working = workingContextAnswer(text)
                val query = if (working != null) MemoryWorkingContext.LAST_TRANSACTION_QUERY else text
                val rows = repository.relevant(query, 8)
                log("MEMORY_RECALL count=${rows.size}")
                MemoryBrainOutcome.Recalled(rows, working)
            }
            MemoryDecision.DELETE -> {
                val commandTarget = (MemoryCommandParser.parse(text) as? MemoryCommand.Forget)?.query
                val active = repository.allActive()
                val naturalTarget = NaturalMemoryRequestResolver.resolvePersonTarget(
                    text,
                    active,
                    MemoryWorkingContext.recentPerson
                )
                val target = commandTarget ?: naturalTarget
                    ?: return MemoryBrainOutcome.Rejected("Memory delete target is ambiguous")
                val ok = repository.forgetMatching(target)
                MemoryWorkingContext.transaction(
                    LastMemoryTransaction(
                        decision,
                        oldValue = target,
                        status = if (ok) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.FAILED,
                        failureReason = if (ok) null else "target_not_found_or_delete_not_verified"
                    )
                )
                log("MEMORY_DELETED status=$ok")
                MemoryBrainOutcome.Deleted(ok)
            }
            MemoryDecision.UPDATE -> {
                val correction = BestFriendNameCorrectionParser.parse(text, MemoryWorkingContext.recentPerson)
                    ?: return MemoryBrainOutcome.Rejected("Correction target is ambiguous")
                val ok = repository.renamePerson(correction.oldName, correction.newName)
                MemoryWorkingContext.transaction(
                    LastMemoryTransaction(
                        decision,
                        oldValue = correction.oldName,
                        newValue = correction.newName,
                        status = if (ok) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.FAILED,
                        failureReason = if (ok) null else "target_not_found_or_verification_failed"
                    )
                )
                if (ok) MemoryWorkingContext.person(correction.newName)
                MemoryBrainOutcome.Mutated(
                    if (ok) MemoryWriteResult.Saved("renamed") else MemoryWriteResult.Rejected("Rename was not verified"),
                    true
                )
            }
            MemoryDecision.SAVE -> {
                val explicit = MemoryCommandParser.parse(text) as? MemoryCommand.Remember
                val candidates = (explicit?.let {
                    listOf(
                        it.candidate.copy(
                            explicitlyRequested = true,
                            provenance = MemoryProvenance.USER_EXPLICIT_MEMORY_COMMAND
                        )
                    )
                } ?: NaturalMemoryExtractor.extract(text)) + supplemental
                val uniqueCandidates = candidates.distinctBy { it.stableKey to it.fact }
                var result: MemoryWriteResult = MemoryWriteResult.Rejected("No grounded durable fact")
                for (candidate in uniqueCandidates) {
                    log("MEMORY_CANDIDATE category=${candidate.category} key=${candidate.stableKey} source=${candidate.provenance} confidence=${candidate.confidence}")
                    result = repository.saveGrounded(candidate, explicit != null)
                    if (result is MemoryWriteResult.Saved) MemoryWorkingContext.person(candidate.entityName)
                }
                MemoryWorkingContext.transaction(
                    LastMemoryTransaction(
                        decision,
                        newValue = uniqueCandidates.lastOrNull()?.stableKey,
                        status = if (result is MemoryWriteResult.Saved) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.REJECTED,
                        failureReason = (result as? MemoryWriteResult.Rejected)?.reason
                    )
                )
                MemoryBrainOutcome.Mutated(result, explicit != null)
            }
            MemoryDecision.NEEDS_CLARIFICATION, MemoryDecision.REJECT ->
                MemoryBrainOutcome.Rejected("Memory operation was not authorized")
        }
    }

    private fun workingContextAnswer(text: String): String? {
        if (!Regex("\\b(?:fail|failed|nahi\\s+ho|not\\s+update)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return MemoryWorkingContext.failedTransactionFact()
    }

    private fun log(message: String) = runCatching { Log.d("LyraMemoryBrainV2", message) }
}
