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

/** Short-lived process context; never persists transcript text or screen content. */
object MemoryWorkingContext {
    @Volatile var lastTransaction: LastMemoryTransaction? = null
        private set
    @Volatile var recentPerson: String? = null
        private set

    fun transaction(value: LastMemoryTransaction) { lastTransaction = value }
    fun person(value: String?) { if (!value.isNullOrBlank()) recentPerson = value.trim() }
    fun clear() { lastTransaction = null; recentPerson = null }
}

/** Revisioned same-session index. Writes invalidate it immediately. */
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

object MemoryIntentClassifier {
    private val questionStart = Regex("^(?:kya|kaun|kaunsa|kaunsi|kiska|kis|kab|where|what|who|which|when|do you remember|tumhe).+", RegexOption.IGNORE_CASE)
    private val questionSubject = Regex("\b(?:yaad|memory|remember|update|delete|naam|project|friend)\b", RegexOption.IGNORE_CASE)

    fun isMemoryQuestion(text: String): Boolean {
        val clean = text.trim()
        return (clean.endsWith("?") || questionStart.containsMatchIn(clean)) &&
            questionSubject.containsMatchIn(clean)
    }

    fun decision(text: String): MemoryDecision {
        if (isMemoryQuestion(text)) return MemoryDecision.RECALL
        return when (MemoryCommandParser.parse(text)) {
            is MemoryCommand.Remember -> MemoryDecision.SAVE
            is MemoryCommand.Read -> MemoryDecision.RECALL
            is MemoryCommand.Forget -> MemoryDecision.DELETE
            null -> if (BestFriendNameCorrectionParser.parse(text, MemoryWorkingContext.recentPerson) != null) {
                MemoryDecision.UPDATE
            } else if (NaturalMemoryExtractor.extract(text).isNotEmpty()) MemoryDecision.SAVE else MemoryDecision.IGNORE
        }
    }
}

/** Bounded extraction helpers; persistence authority remains MemoryBrainCoordinator. */
object NaturalMemoryExtractor {
    private val friend = Regex("(?:mera|meri|my)\s+(?:friend|dost)\s+([\\p{L}][\\p{L} .'-]{1,40}?)(?:\s+hai|$)", RegexOption.IGNORE_CASE)
    private val travel = Regex("(?:main|i)\s+([\\p{L}][\\p{L} .'-]{1,40}?)\s+ke\s+saath\s+(.+?)\s+(?:gaya|gayi|travel(?:led)?)(?:\s+tha|\s+thi|$)", RegexOption.IGNORE_CASE)

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
        if (direct.isNotEmpty()) return direct
        val linked = PersonLinkedMemoryExtractor.extractAll(clean)
        if (linked.isNotEmpty()) return linked.map { it.copy(provenance = MemoryProvenance.USER_DIRECT_STATEMENT) }
        PersonalMemoryExtractor.extract(clean)?.let { return listOf(it.copy(provenance = MemoryProvenance.USER_DIRECT_STATEMENT)) }
        return when (val change = AutomaticMemoryChangeParser.parse(clean)) {
            is AutomaticMemoryChange.Save -> listOf(change.candidate.copy(provenance = MemoryProvenance.USER_DIRECT_STATEMENT))
            else -> emptyList()
        }
    }

    private fun canonicalName(value: String) = value.trim().split(Regex("\\s+")).joinToString(" ") {
        it.lowercase(Locale.ROOT).replaceFirstChar { char -> char.uppercase() }
    }
    private fun token(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
    fun stablePersonId(name: String) = "person-${UUID.nameUUIDFromBytes(token(name).toByteArray())}"
}

class MemoryBrainCoordinator(private val repository: MemoryRepository) {
    suspend fun processFinalTurn(text: String): MemoryBrainOutcome {
        val decision = MemoryIntentClassifier.decision(text)
        log("MEMORY_DECISION decision=$decision")
        return when (decision) {
            MemoryDecision.IGNORE -> MemoryBrainOutcome.Ignored
            MemoryDecision.RECALL -> {
                val working = workingContextAnswer(text)
                val rows = repository.relevant(text, 8)
                log("MEMORY_RECALL count=${rows.size}")
                MemoryBrainOutcome.Recalled(rows, working)
            }
            MemoryDecision.DELETE -> {
                val command = MemoryCommandParser.parse(text) as? MemoryCommand.Forget
                    ?: return MemoryBrainOutcome.Rejected("No resolvable delete target")
                val ok = repository.forgetMatching(command.query)
                MemoryWorkingContext.transaction(LastMemoryTransaction(decision, oldValue = command.query,
                    status = if (ok) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.FAILED,
                    failureReason = if (ok) null else "target_not_found"))
                log("MEMORY_DELETED status=$ok")
                MemoryBrainOutcome.Deleted(ok)
            }
            MemoryDecision.UPDATE -> {
                val correction = BestFriendNameCorrectionParser.parse(text, MemoryWorkingContext.recentPerson)
                    ?: return MemoryBrainOutcome.Rejected("Correction target is ambiguous")
                val ok = repository.renamePerson(correction.oldName, correction.newName)
                MemoryWorkingContext.transaction(LastMemoryTransaction(decision, oldValue = correction.oldName,
                    newValue = correction.newName,
                    status = if (ok) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.FAILED,
                    failureReason = if (ok) null else "target_not_found_or_verification_failed"))
                if (ok) MemoryWorkingContext.person(correction.newName)
                MemoryBrainOutcome.Mutated(if (ok) MemoryWriteResult.Saved("renamed") else MemoryWriteResult.Rejected("Rename was not verified"), true)
            }
            MemoryDecision.SAVE -> {
                val explicit = MemoryCommandParser.parse(text) as? MemoryCommand.Remember
                val candidates = explicit?.let { listOf(it.candidate.copy(
                    explicitlyRequested = true,
                    provenance = MemoryProvenance.USER_EXPLICIT_MEMORY_COMMAND
                )) } ?: NaturalMemoryExtractor.extract(text)
                var result: MemoryWriteResult = MemoryWriteResult.Rejected("No grounded durable fact")
                for (candidate in candidates) {
                    log("MEMORY_CANDIDATE category=${candidate.category} key=${candidate.stableKey} source=${candidate.provenance} confidence=${candidate.confidence}")
                    result = repository.saveGrounded(candidate, explicit != null)
                    if (result is MemoryWriteResult.Saved) MemoryWorkingContext.person(candidate.entityName)
                }
                MemoryWorkingContext.transaction(LastMemoryTransaction(decision,
                    status = if (result is MemoryWriteResult.Saved) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.REJECTED,
                    failureReason = (result as? MemoryWriteResult.Rejected)?.reason))
                MemoryBrainOutcome.Mutated(result, explicit != null)
            }
            MemoryDecision.NEEDS_CLARIFICATION, MemoryDecision.REJECT -> MemoryBrainOutcome.Rejected("Memory operation was not authorized")
        }
    }

    private fun workingContextAnswer(text: String): String? {
        val transaction = MemoryWorkingContext.lastTransaction ?: return null
        if (transaction.status != MemoryTransactionStatus.FAILED) return null
        if (!Regex("\\b(?:fail|failed|nahi\\s+ho|not\\s+update)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return transaction.newValue ?: transaction.oldValue
    }

    private fun log(message: String) = runCatching { Log.d("LyraMemoryBrainV2", message) }
}
