package com.myra.assistant.data.memory

import android.util.Log

enum class MemoryDecision { IGNORE, RECALL, SAVE, UPDATE, DELETE, TRANSIENT, NEEDS_CLARIFICATION, REJECT }
enum class MemoryRecallType { GENERAL, FRIENDS, BEST_FRIEND, LAST_TRANSACTION, EPISODES, GOALS, PROJECTS }
enum class MemoryTransactionStatus { SUCCEEDED, FAILED, REJECTED, TRANSIENT }

data class AuthoritativeMemoryTurnEvidence(
    val turnId: Long, val canonicalText: String, val displayText: String,
    val protectedCanonicalNames: List<String> = emptyList(), val protectedDisplayNames: List<String> = emptyList(),
    val sessionId: String = "compatibility", val utteranceId: String = "$sessionId:$turnId",
    val contextGeneration: Long = turnId
) {
    val variants = listOf(canonicalText, displayText).map(String::trim).filter(String::isNotEmpty).distinct()
    val sourceText get() = displayText.ifBlank { canonicalText }
}

data class EpisodicMemoryPayload(val eventType: String, val summary: String, val participants: List<String>, val importance: Double = .5)
data class GoalMemoryPayload(val title: String, val description: String?, val status: String = "ACTIVE",
    val priority: Int = 0, val progress: Int = 0, val deadline: Long? = null, val parentGoalId: String? = null)

object AiriWorkingMemory {
    const val LAST_TRANSACTION_QUERY = "__last_memory_transaction__"
    @Volatile var lastTransaction: VerifiedMemoryTransaction? = null; private set
    fun record(value: VerifiedMemoryTransaction) {
        lastTransaction = value
        AiriMemoryRuntime.contexts.ingest(ContextEntry("last_memory_transaction", value.status.name,
            value.turnId, value.turnId), ContextMutation.REPLACE_SELF)
    }
    fun transient(turn: AuthoritativeMemoryTurnEvidence, value: String, ttlMs: Long = 86_400_000L) =
        AiriMemoryRuntime.contexts.ingest(ContextEntry("temporary_instruction", value, turn.turnId,
            turn.contextGeneration, expiresAt = System.currentTimeMillis() + ttlMs), ContextMutation.REPLACE_SELF)
    fun transactionAnswer(): String? = lastTransaction?.let { tx -> when (tx.status) {
        MemoryTransactionStatus.SUCCEEDED -> "The last memory operation completed and was verified."
        MemoryTransactionStatus.TRANSIENT -> "The last instruction is temporary and was not added to long-term memory."
        MemoryTransactionStatus.FAILED -> "The last memory operation failed verification."
        MemoryTransactionStatus.REJECTED -> "The last memory operation was not saved${tx.reason?.let { ": ${it.name.lowercase().replace('_', ' ')}" }.orEmpty()}."
    } }
    fun clear() { lastTransaction = null; AiriMemoryRuntime.contexts.clear() }
}

sealed class MemoryBrainOutcome {
    data object Ignored : MemoryBrainOutcome()
    data class Recalled(val rows: List<MemoryEntity>, val workingAnswer: String? = null,
        val type: MemoryRecallType = MemoryRecallType.GENERAL, val durationMs: Long = 0) : MemoryBrainOutcome()
    data class Mutated(val result: MemoryWriteResult, val explicit: Boolean = false) : MemoryBrainOutcome()
    data class Deleted(val succeeded: Boolean) : MemoryBrainOutcome()
    data class Transient(val accepted: Boolean = true) : MemoryBrainOutcome()
    data class Rejected(val reason: String, val code: MemoryFailureReason) : MemoryBrainOutcome()
}

/** One final-turn owner. It is the only class allowed to authorize consolidated writes. */
class MemoryBrainCoordinator(private val store: AiriMemoryStore) {
    suspend fun prepareFinalTurn(evidence: AuthoritativeMemoryTurnEvidence, staged: List<MemorySemanticFrame>, semanticConsistent: Boolean = true): FinalMemoryTurnPlan {
        val bounded = staged.take(4)
        if (bounded.isEmpty()) return FinalMemoryTurnPlan(evidence.sourceText, decision = MemoryDecision.IGNORE)
        if (!semanticConsistent) return FinalMemoryTurnPlan(evidence.sourceText, decision = MemoryDecision.REJECT, rejectionReason = MemoryFailureReason.CRITICAL_LITERAL_MISSING.name)
        val isQuestion = bounded.any { it.intent == MemorySemanticIntent.RECALL }
        if (isQuestion && bounded.any { it.intent !in setOf(MemorySemanticIntent.RECALL, MemorySemanticIntent.CLARIFY) })
            return FinalMemoryTurnPlan(evidence.sourceText, decision = MemoryDecision.REJECT, rejectionReason = MemoryFailureReason.QUESTION_MUTATION.name)

        val resolved = mutableListOf<MemorySemanticFrame>()
        for (raw in bounded) {
            val frame = raw.copy(sourceSessionId = evidence.sessionId)
            val authorization = FinalTurnSourceSpanAuthorizer.authorize(frame, evidence, evidence.turnId, isQuestion)
            log("MEMORY_CONSOLIDATION_PLAN turnId=${evidence.turnId} operation=${frame.intent} safety=${authorization.reason} authorized=${authorization.authorized}")
            if (!authorization.authorized) return FinalMemoryTurnPlan(evidence.sourceText, decision = MemoryDecision.REJECT, rejectionReason = authorization.reason.name)
            when (frame.intent) {
                MemorySemanticIntent.RENAME_ENTITY, MemorySemanticIntent.REMOVE_RELATIONSHIP,
                MemorySemanticIntent.REPLACE_RELATIONSHIP, MemorySemanticIntent.DELETE_ENTITY,
                MemorySemanticIntent.ADD_LINKED_FACT -> {
                    val name = frame.person ?: return clarify(evidence, MemoryFailureReason.AMBIGUOUS_ENTITY)
                    val matches = store.peopleByName(name)
                    if (matches.size != 1) return clarify(evidence, if (matches.isEmpty()) MemoryFailureReason.TARGET_NOT_FOUND else MemoryFailureReason.AMBIGUOUS_ENTITY)
                    resolved += frame.copy(resolvedEntityId = matches.single().entityId)
                }
                else -> resolved += frame
            }
        }
        val decision = when {
            resolved.all { it.intent == MemorySemanticIntent.RECALL } -> MemoryDecision.RECALL
            resolved.any { it.intent == MemorySemanticIntent.CLARIFY } -> MemoryDecision.NEEDS_CLARIFICATION
            resolved.all { it.intent == MemorySemanticIntent.TRANSIENT_CONTEXT || it.temporalScope == MemoryTemporalScope.TEMPORARY } -> MemoryDecision.TRANSIENT
            resolved.any { it.intent in setOf(MemorySemanticIntent.DELETE_ENTITY, MemorySemanticIntent.REMOVE_RELATIONSHIP) } -> MemoryDecision.DELETE
            resolved.any { it.intent in setOf(MemorySemanticIntent.UPDATE_FACT, MemorySemanticIntent.SUPERSEDE_FACT, MemorySemanticIntent.RENAME_ENTITY, MemorySemanticIntent.REPLACE_RELATIONSHIP) } -> MemoryDecision.UPDATE
            else -> MemoryDecision.SAVE
        }
        return FinalMemoryTurnPlan(evidence.sourceText, resolved, decision, decision == MemoryDecision.NEEDS_CLARIFICATION,
            MemoryFailureReason.AMBIGUOUS_ENTITY.name.takeIf { decision == MemoryDecision.NEEDS_CLARIFICATION })
    }

    suspend fun executeFinalTurnPlan(plan: FinalMemoryTurnPlan, evidence: AuthoritativeMemoryTurnEvidence? = null): MemoryBrainOutcome {
        val turn = evidence ?: AuthoritativeMemoryTurnEvidence(
            plan.operations.firstOrNull()?.sourceTurnId ?: 0L, plan.sourceText, plan.sourceText)
        if (!AiriMemoryRuntime.isCurrent(turn.sessionId, turn.turnId)) return reject(turn.turnId, MemoryFailureReason.STALE_TURN)
        if (plan.requiresClarification) return reject(turn.turnId, MemoryFailureReason.AMBIGUOUS_ENTITY)
        if (plan.decision == MemoryDecision.REJECT) return reject(turn.turnId,
            runCatching { MemoryFailureReason.valueOf(plan.rejectionReason.orEmpty()) }.getOrDefault(MemoryFailureReason.UNSUPPORTED_OPERATION))
        if (plan.decision == MemoryDecision.IGNORE) return MemoryBrainOutcome.Ignored
        if (plan.decision == MemoryDecision.RECALL) {
            val frame = plan.operations.first(); return recall(frame.fact.orEmpty(), 8, recallType(frame))
        }
        var lastId: String? = null; var deleted = false; var transient = false
        for (frame in plan.operations) {
            if (frame.temporalScope == MemoryTemporalScope.TEMPORARY) {
                transient = AiriWorkingMemory.transient(turn, frame.fact ?: plan.sourceText)
                continue
            }
            when (frame.intent) {
                MemorySemanticIntent.TRANSIENT_CONTEXT -> { transient = AiriWorkingMemory.transient(turn, frame.fact ?: plan.sourceText) }
                MemorySemanticIntent.ADD_RELATIONSHIP -> {
                    val name = frame.person ?: return reject(turn.turnId, MemoryFailureReason.AMBIGUOUS_ENTITY)
                    val person = store.ensurePerson(name, turn.turnId)
                    lastId = store.addRelationship(person.entityId, frame.relationship ?: return reject(turn.turnId, MemoryFailureReason.UNSUPPORTED_OPERATION), turn, frame.confidence)
                }
                MemorySemanticIntent.REMOVE_RELATIONSHIP -> deleted = store.endRelationship(frame.resolvedEntityId!!,
                    frame.relationship ?: return reject(turn.turnId, MemoryFailureReason.UNSUPPORTED_OPERATION))
                MemorySemanticIntent.REPLACE_RELATIONSHIP -> lastId = store.addRelationship(frame.resolvedEntityId!!,
                    frame.replacementRelationship ?: return reject(turn.turnId, MemoryFailureReason.UNSUPPORTED_OPERATION), turn, frame.confidence)
                MemorySemanticIntent.RENAME_ENTITY -> {
                    val replacement = frame.replacementPerson ?: return reject(turn.turnId, MemoryFailureReason.AMBIGUOUS_ENTITY)
                    if (!store.renamePerson(frame.resolvedEntityId!!, replacement, turn.turnId)) return reject(turn.turnId, MemoryFailureReason.VERIFY_FAILED)
                    lastId = frame.resolvedEntityId
                }
                MemorySemanticIntent.DELETE_ENTITY -> deleted = store.deletePerson(frame.resolvedEntityId!!)
                MemorySemanticIntent.ADD_EPISODE -> {
                    val ids = frame.episode?.participants.orEmpty().map { store.ensurePerson(it, turn.turnId).entityId }
                    lastId = store.addEpisode(frame, turn, ids)
                }
                MemorySemanticIntent.ADD_GOAL -> lastId = store.addGoal(frame, turn)
                MemorySemanticIntent.ADD_FACT, MemorySemanticIntent.UPDATE_FACT,
                MemorySemanticIntent.SUPERSEDE_FACT, MemorySemanticIntent.ADD_LINKED_FACT -> lastId = store.addSemantic(frame, turn)
                MemorySemanticIntent.RECALL, MemorySemanticIntent.CLARIFY, MemorySemanticIntent.NONE -> Unit
            }
            if (!transient && !deleted && frame.intent !in setOf(MemorySemanticIntent.RECALL, MemorySemanticIntent.CLARIFY, MemorySemanticIntent.NONE) && lastId == null)
                return reject(turn.turnId, MemoryFailureReason.VERIFY_FAILED)
        }
        val status = if (transient && lastId == null && !deleted) MemoryTransactionStatus.TRANSIENT else MemoryTransactionStatus.SUCCEEDED
        AiriWorkingMemory.record(VerifiedMemoryTransaction(turn.turnId, plan.operations.last().intent, status, lastId))
        return when { transient && lastId == null && !deleted -> MemoryBrainOutcome.Transient(); deleted -> MemoryBrainOutcome.Deleted(true); else -> MemoryBrainOutcome.Mutated(MemoryWriteResult.Saved(lastId!!)) }
    }

    suspend fun recall(query: String, limit: Int = 8, type: MemoryRecallType = MemoryRecallType.GENERAL): MemoryBrainOutcome.Recalled {
        val started = System.nanoTime()
        val answer = if (type == MemoryRecallType.LAST_TRANSACTION) AiriWorkingMemory.transactionAnswer() else null
        val rows = if (answer != null) emptyList() else store.retrieve(query, type, limit)
        return MemoryBrainOutcome.Recalled(rows, answer, type, (System.nanoTime() - started) / 1_000_000)
    }

    suspend fun captureConversation(evidence: AuthoritativeMemoryTurnEvidence, assistantText: String?) {
        val now = System.currentTimeMillis(); val base = evidence.turnId * 2
        store.appendConversation(ConversationTruthEntity("${evidence.utteranceId}:user", evidence.sessionId, base,
            evidence.turnId, evidence.utteranceId, "user", evidence.canonicalText, now))
        assistantText?.takeIf(String::isNotBlank)?.let { store.appendConversation(ConversationTruthEntity(
            "${evidence.utteranceId}:assistant", evidence.sessionId, base + 1, evidence.turnId,
            evidence.utteranceId, "assistant", it, now)) }
    }

    private fun recallType(frame: MemorySemanticFrame): MemoryRecallType = runCatching {
        MemoryRecallType.valueOf(frame.stableKey?.uppercase().orEmpty())
    }.getOrDefault(MemoryRecallType.GENERAL)
    private fun clarify(e: AuthoritativeMemoryTurnEvidence, reason: MemoryFailureReason) = FinalMemoryTurnPlan(
        e.sourceText, decision = MemoryDecision.NEEDS_CLARIFICATION, requiresClarification = true, rejectionReason = reason.name)
    private fun reject(turnId: Long, reason: MemoryFailureReason): MemoryBrainOutcome.Rejected {
        AiriWorkingMemory.record(VerifiedMemoryTransaction(turnId, MemorySemanticIntent.NONE, MemoryTransactionStatus.REJECTED, reason = reason))
        return MemoryBrainOutcome.Rejected(reason.name.lowercase().replace('_', ' '), reason)
    }
    private fun log(value: String) = runCatching { Log.d("LyraAiriMemory", value) }
}
