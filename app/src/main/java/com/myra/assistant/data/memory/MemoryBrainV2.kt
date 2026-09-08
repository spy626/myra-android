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

/**
 * Small verified working context, not a transcript buffer. It exists so natural references
 * such as "usko", "this memory", or questions about the last failed operation can resolve
 * against real memory state instead of rigid command phrasing.
 */
object MemoryWorkingContext {
    const val LAST_TRANSACTION_QUERY = "__last_memory_transaction__"

    @Volatile var lastTransaction: LastMemoryTransaction? = null
        private set
    @Volatile var recentPerson: String? = null
        private set
    @Volatile var recentMemoryId: String? = null
        private set
    @Volatile var recentMemoryKey: String? = null
        private set
    @Volatile var recentMemoryFact: String? = null
        private set
    @Volatile var recentMemoryCategory: String? = null
        private set
    @Volatile var recentMemoryAt: Long = 0L
        private set

    fun transaction(value: LastMemoryTransaction) { lastTransaction = value }
    fun person(value: String?) { if (!value.isNullOrBlank()) recentPerson = value.trim() }

    fun memory(value: MemoryEntity?) {
        if (value == null || value.id == "working:last_memory_transaction") return
        recentMemoryId = value.id
        recentMemoryKey = value.stableKey
        recentMemoryFact = value.fact
        recentMemoryCategory = value.category
        recentMemoryAt = System.currentTimeMillis()
        value.entityName?.let(::person)
    }

    fun recalled(rows: List<MemoryEntity>) {
        val durable = rows.filterNot { it.id == "working:last_memory_transaction" }
        if (durable.size == 1) {
            memory(durable.single())
            return
        }
        clearRecentMemory()
        val people = durable.mapNotNull { it.entityName }
            .distinctBy { normalize(it) }
        if (people.size == 1) person(people.single())
    }

    fun clearRecentMemory() {
        recentMemoryId = null
        recentMemoryKey = null
        recentMemoryFact = null
        recentMemoryCategory = null
        recentMemoryAt = 0L
    }

    fun clearPersonIfMatches(value: String) {
        val current = recentPerson ?: return
        if (normalize(current) == normalize(value) || BestFriendNameSimilarity.likelySame(current, value)) {
            recentPerson = null
        }
    }

    fun clear() {
        lastTransaction = null
        recentPerson = null
        clearRecentMemory()
    }

    /** Human-readable short working memory for natural questions about the last memory operation. */
    fun transactionFact(): String? {
        val value = lastTransaction ?: return null
        return when (value.status) {
            MemoryTransactionStatus.SUCCEEDED -> when (value.type) {
                MemoryDecision.UPDATE -> when {
                    !value.oldValue.isNullOrBlank() && !value.newValue.isNullOrBlank() ->
                        "The last memory update changed ${value.oldValue} to ${value.newValue}."
                    !value.newValue.isNullOrBlank() -> "The last memory update was for ${value.newValue}."
                    else -> "The last memory update was completed and verified."
                }
                MemoryDecision.DELETE -> value.oldValue?.takeIf { it.isNotBlank() }
                    ?.let { "The last memory deletion was for $it." }
                    ?: "The last memory deletion was completed and verified."
                MemoryDecision.SAVE -> value.newValue?.takeIf { it.isNotBlank() }
                    ?.let { "The last memory saved was: $it" }
                    ?: "The last memory save was completed and verified."
                else -> "The last memory operation completed successfully."
            }
            MemoryTransactionStatus.FAILED -> when (value.type) {
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
            MemoryTransactionStatus.REJECTED -> value.failureReason?.takeIf { it.isNotBlank() }
                ?.let { "The last memory operation was not saved: $it." }
                ?: "The last memory operation was rejected by the memory safety gate."
        }
    }

    /** Kept for repository compatibility; the synthetic working-memory row represents the last transaction. */
    fun failedTransactionFact(): String? = transactionFact()

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
}

/** Recognizes natural questions about a past memory operation; it does not create a mutation command. */
object MemoryTransactionQueryDetector {
    private val operation = Regex(
        "\\b(?:update|updated|change|changed|rename|renamed|delete|deleted|remove|removed|save|saved|remember|remembered)\\b",
        RegexOption.IGNORE_CASE
    )
    private val question = Regex(
        "\\b(?:kya|kaun|kaunsa|kaunsi|kiska|kis|kab|what|which|who|when)\\b",
        RegexOption.IGNORE_CASE
    )
    private val failure = Regex(
        "\\b(?:fail|failed|failure|nahi\\s+(?:ho\\s+)?(?:paya|payi|hua|hui)|didn'?t|did\\s+not|wasn'?t|was\\s+not)\\b",
        RegexOption.IGNORE_CASE
    )
    private val past = Regex(
        "\\b(?:maine|last|pichla|pichli|pichle|tha|thi|hua|hui|kiya|ki|did\\s+i|had\\s+i|was|were)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isTransactionQuestion(text: String): Boolean {
        val clean = text.trim()
        return operation.containsMatchIn(clean) && question.containsMatchIn(clean) &&
            (failure.containsMatchIn(clean) || past.containsMatchIn(clean))
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
        "\\b(?:forget|delete|remove|bh(?:oo|u)l(?:e|na)?|hata(?:o|na)?)\\b(?:\\s+(?:jao|jaao|do|dena))?",
        RegexOption.IGNORE_CASE
    )
    private val protectedNegation = Regex(
        "\\b(?:mat|dont|don't|do\\s+not|nahi|nahin)\\s+(?:bh(?:oo|u)l(?:e|na)?|forget|delete|remove|hata(?:o|na)?)\\b",
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
            is MemoryCommand.Edit -> MemoryDecision.UPDATE
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
    data class FinalTurnAssessment(
        val decision: MemoryDecision,
        val correction: BestFriendNameCorrection? = null,
        val correctionIntentDetected: Boolean = false,
        val requiresClarification: Boolean = false,
        val rejectionReason: String? = null,
        val databaseMutationAllowed: Boolean = correction != null
    )

    /** Pure final-turn semantic assessment used by the voice layer only for response ownership. */
    fun assessFinalTurn(
        text: String,
        supplemental: List<MemoryCandidate> = emptyList(),
        semanticConsistent: Boolean = true
    ): FinalTurnAssessment {
        val classified = MemoryIntentClassifier.decision(text)
        val decision = if (classified == MemoryDecision.IGNORE && supplemental.isNotEmpty()) {
            MemoryDecision.SAVE
        } else classified
        if (decision != MemoryDecision.UPDATE || MemoryCommandParser.parse(text) is MemoryCommand.Edit) {
            return FinalTurnAssessment(decision)
        }
        val analysis = BestFriendNameCorrectionParser.analyze(text, MemoryWorkingContext.recentPerson)
        val authorized = analysis.correction != null && semanticConsistent
        return FinalTurnAssessment(
            decision = decision,
            correction = analysis.correction?.takeIf { authorized },
            correctionIntentDetected = analysis.correctionIntentDetected,
            requiresClarification = analysis.correctionIntentDetected && !authorized,
            rejectionReason = analysis.rejectionReason ?: if (!semanticConsistent) "semantic_name_mismatch" else null,
            databaseMutationAllowed = authorized
        )
    }

    fun needsCorrectionClarification(text: String): Boolean =
        BestFriendNameCorrectionParser.needsClearCorrectedName(text)

    fun ambiguousCorrectionTarget(text: String, recentName: String?): String? =
        BestFriendNameCorrectionParser.ambiguousOldName(text, recentName)

    fun resolveCorrectionAnswer(text: String): ClarifiedNameResult =
        ClarifiedPersonNameResolver.resolve(text)

    fun explicitCommandDecision(text: String): MemoryDecision? = when (MemoryCommandParser.parse(text)) {
        is MemoryCommand.Remember -> MemoryDecision.SAVE
        is MemoryCommand.Read -> MemoryDecision.RECALL
        is MemoryCommand.Forget -> MemoryDecision.DELETE
        is MemoryCommand.Edit -> MemoryDecision.UPDATE
        null -> null
    }

    suspend fun processGroundedProposal(candidate: MemoryCandidate): MemoryWriteResult {
        log("MEMORY_CANDIDATE category=${candidate.category} key=${candidate.stableKey} source=${candidate.provenance} confidence=${candidate.confidence}")
        val result = repository.saveGrounded(candidate)
        MemoryWorkingContext.transaction(
            LastMemoryTransaction(
                type = MemoryDecision.SAVE,
                newValue = candidate.fact,
                status = if (result is MemoryWriteResult.Saved) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.REJECTED,
                failureReason = (result as? MemoryWriteResult.Rejected)?.reason
            )
        )
        rememberSavedResult(result)
        log("MEMORY_TRANSACTION decision=SAVE status=${result::class.simpleName}")
        return result
    }

    /** All explicit memory commands converge here; service code never mutates Room directly. */
    suspend fun processCommand(command: MemoryCommand, recallLimit: Int = 5): MemoryBrainOutcome = when (command) {
        is MemoryCommand.Remember -> saveCandidates(
            listOf(
                command.candidate.copy(
                    explicitlyRequested = true,
                    provenance = MemoryProvenance.USER_EXPLICIT_MEMORY_COMMAND
                )
            ),
            explicit = true
        )
        is MemoryCommand.Read -> recall(command.query, recallLimit)
        is MemoryCommand.Forget -> deleteTarget(command.query)
        is MemoryCommand.Edit -> editTarget(command)
    }

    /** Read-only path used by Gemini Live and deterministic recall. It owns no speech. */
    suspend fun recall(query: String, limit: Int = 8): MemoryBrainOutcome.Recalled {
        val workingAnswer = if (query == MemoryWorkingContext.LAST_TRANSACTION_QUERY) {
            MemoryWorkingContext.transactionFact()
        } else null
        val rows = repository.relevant(query, limit)

        // An explicit generic "what do you remember" query is a real recall even though
        // the repository's blank-query startup path intentionally does not mark usage.
        if (query.isBlank() && rows.isNotEmpty()) {
            rows.forEach { row -> repository.relevant(row.fact, 1) }
            MemorySessionIndex.publish(rows)
        }
        MemoryWorkingContext.recalled(rows)
        log("MEMORY_RECALL count=${rows.size}")
        return MemoryBrainOutcome.Recalled(rows, workingAnswer)
    }

    /** Verified person correction entry for both natural final turns and clarification turns. */
    suspend fun processPersonRename(correction: BestFriendNameCorrection): MemoryBrainOutcome {
        val validationFailure = BestFriendNameCorrectionParser.validateNewName(correction.newName)
        if (validationFailure != null || correction.oldName.equals(correction.newName, ignoreCase = true)) {
            MemoryWorkingContext.transaction(
                LastMemoryTransaction(
                    MemoryDecision.UPDATE,
                    oldValue = correction.oldName,
                    newValue = correction.newName,
                    status = MemoryTransactionStatus.REJECTED,
                    failureReason = validationFailure ?: "old_and_new_names_are_identical"
                )
            )
            return MemoryBrainOutcome.Rejected(validationFailure ?: "Old and new names are identical")
        }
        val ok = repository.renamePerson(correction.oldName, correction.newName)
        MemoryWorkingContext.transaction(
            LastMemoryTransaction(
                MemoryDecision.UPDATE,
                oldValue = correction.oldName,
                newValue = correction.newName,
                status = if (ok) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.FAILED,
                failureReason = if (ok) null else "target_not_found_or_verification_failed"
            )
        )
        if (ok) {
            MemoryWorkingContext.person(correction.newName)
            MemoryWorkingContext.clearRecentMemory()
        }
        log("MEMORY_TRANSACTION decision=UPDATE status=$ok")
        return MemoryBrainOutcome.Mutated(
            if (ok) MemoryWriteResult.Saved("renamed")
            else MemoryWriteResult.Rejected("Rename was not verified"),
            true
        )
    }

    suspend fun processFinalTurn(text: String, supplemental: List<MemoryCandidate> = emptyList()): MemoryBrainOutcome {
        val parsedCommand = MemoryCommandParser.parse(text)
        val decision = assessFinalTurn(text, supplemental).decision
        log("MEMORY_DECISION decision=$decision")
        return when (decision) {
            MemoryDecision.IGNORE -> MemoryBrainOutcome.Ignored
            MemoryDecision.RECALL -> {
                if (parsedCommand is MemoryCommand.Read) processCommand(parsedCommand, 8)
                else recall(text, 8)
            }
            MemoryDecision.DELETE -> {
                if (parsedCommand is MemoryCommand.Forget) processCommand(parsedCommand)
                else {
                    val active = repository.allActive()
                    val naturalTarget = NaturalMemoryRequestResolver.resolvePersonTarget(
                        text,
                        active,
                        MemoryWorkingContext.recentPerson
                    ) ?: return MemoryBrainOutcome.Rejected("Memory delete target is ambiguous")
                    deleteTarget(naturalTarget)
                }
            }
            MemoryDecision.UPDATE -> {
                if (parsedCommand is MemoryCommand.Edit) processCommand(parsedCommand)
                else {
                    val correction = BestFriendNameCorrectionParser.parse(text, MemoryWorkingContext.recentPerson)
                        ?: return MemoryBrainOutcome.Rejected("Correction target is ambiguous")
                    processPersonRename(correction)
                }
            }
            MemoryDecision.SAVE -> {
                if (parsedCommand is MemoryCommand.Remember) processCommand(parsedCommand)
                else {
                    val candidates = NaturalMemoryExtractor.extract(text) + supplemental
                    saveCandidates(candidates, explicit = false)
                }
            }
            MemoryDecision.NEEDS_CLARIFICATION, MemoryDecision.REJECT ->
                MemoryBrainOutcome.Rejected("Memory operation was not authorized")
        }
    }

    private suspend fun deleteTarget(rawTarget: String): MemoryBrainOutcome {
        val target = rawTarget.trim()
        if (target.isBlank()) return MemoryBrainOutcome.Rejected("Memory delete target is ambiguous")
        val ok = repository.forgetMatching(target)
        if (ok) {
            MemoryWorkingContext.clearPersonIfMatches(target)
            MemoryWorkingContext.clearRecentMemory()
            MemorySessionIndex.invalidate()
        }
        MemoryWorkingContext.transaction(
            LastMemoryTransaction(
                MemoryDecision.DELETE,
                oldValue = target,
                status = if (ok) MemoryTransactionStatus.SUCCEEDED else MemoryTransactionStatus.FAILED,
                failureReason = if (ok) null else "target_not_found_or_delete_not_verified"
            )
        )
        log("MEMORY_DELETED status=$ok")
        return MemoryBrainOutcome.Deleted(ok)
    }

    private suspend fun editTarget(command: MemoryCommand.Edit): MemoryBrainOutcome {
        val replacement = command.replacement.trim().replace(Regex("\\s+"), " ")
        if (replacement.length !in 2..200) {
            return MemoryBrainOutcome.Rejected("Tell me the new memory value clearly")
        }
        val active = repository.allActive()
        val target = if (!command.targetQuery.isNullOrBlank()) {
            MemoryRelevanceSelector.select(command.targetQuery, active, 2).singleOrNull()
        } else {
            MemoryWorkingContext.recentMemoryId?.let { id -> active.firstOrNull { it.id == id } }
        } ?: return MemoryBrainOutcome.Rejected("Memory edit target is ambiguous; recall or name the memory first")

        if (target.category == MemoryCategory.PERSON.name) {
            val oldName = target.entityName ?: MemoryRelationshipPolicy.personName(target.fact)
                ?: return MemoryBrainOutcome.Rejected("Person edit target could not be resolved")
            val newName = when (val resolved = ClarifiedPersonNameResolver.resolve(replacement)) {
                is ClarifiedNameResult.Accepted -> resolved.name
                else -> MemoryRelationshipPolicy.personName(replacement)
            } ?: return MemoryBrainOutcome.Rejected("Correct person name is unclear")
            return processPersonRename(BestFriendNameCorrection(oldName, newName))
        }

        val category = runCatching { MemoryCategory.valueOf(target.category) }.getOrNull()
            ?: return MemoryBrainOutcome.Rejected("Memory category is invalid")
        val sensitivity = runCatching { MemorySensitivity.valueOf(target.sensitivity) }
            .getOrDefault(MemorySensitivity.LOW)
        val candidate = MemoryCandidate(
            category = category,
            fact = replacement,
            stableKey = target.stableKey,
            sensitivity = sensitivity,
            confidence = 1.0,
            explicitlyRequested = true,
            source = "explicit_memory_edit",
            provenance = MemoryProvenance.USER_EXPLICIT_MEMORY_COMMAND,
            entityId = target.entityId,
            entityName = target.entityName,
            observationMetadata = target.observationMetadata
        )
        val result = repository.saveGrounded(candidate, explicit = true)
        MemoryWorkingContext.transaction(
            LastMemoryTransaction(
                MemoryDecision.UPDATE,
                targetId = target.id,
                oldValue = target.fact,
                newValue = replacement,
                status = if (result is MemoryWriteResult.Saved) MemoryTransactionStatus.SUCCEEDED
                    else MemoryTransactionStatus.REJECTED,
                failureReason = (result as? MemoryWriteResult.Rejected)?.reason
            )
        )
        rememberSavedResult(result)
        return MemoryBrainOutcome.Mutated(result, true)
    }

    private suspend fun saveCandidates(
        candidates: List<MemoryCandidate>,
        explicit: Boolean
    ): MemoryBrainOutcome {
        val uniqueCandidates = candidates.distinctBy { it.stableKey to it.fact }
        if (uniqueCandidates.isEmpty()) return MemoryBrainOutcome.Rejected("No grounded durable fact")
        var result: MemoryWriteResult = MemoryWriteResult.Rejected("No grounded durable fact")
        for (candidate in uniqueCandidates) {
            val grounded = if (explicit) candidate.copy(
                explicitlyRequested = true,
                provenance = MemoryProvenance.USER_EXPLICIT_MEMORY_COMMAND
            ) else candidate
            log("MEMORY_CANDIDATE category=${grounded.category} key=${grounded.stableKey} source=${grounded.provenance} confidence=${grounded.confidence}")
            result = repository.saveGrounded(grounded, explicit)
            if (result is MemoryWriteResult.Saved) {
                MemoryWorkingContext.person(grounded.entityName)
                rememberSavedResult(result)
            }
        }
        MemoryWorkingContext.transaction(
            LastMemoryTransaction(
                MemoryDecision.SAVE,
                newValue = uniqueCandidates.lastOrNull()?.fact,
                status = if (result is MemoryWriteResult.Saved) MemoryTransactionStatus.SUCCEEDED
                    else MemoryTransactionStatus.REJECTED,
                failureReason = (result as? MemoryWriteResult.Rejected)?.reason
            )
        )
        return MemoryBrainOutcome.Mutated(result, explicit)
    }

    private suspend fun rememberSavedResult(result: MemoryWriteResult) {
        val id = (result as? MemoryWriteResult.Saved)?.id ?: return
        if (id == "renamed" || id == "existing") return
        repository.allActive().firstOrNull { it.id == id }?.let(MemoryWorkingContext::memory)
    }

    private fun log(message: String) = runCatching { Log.d("LyraMemoryBrainV2", message) }
}
