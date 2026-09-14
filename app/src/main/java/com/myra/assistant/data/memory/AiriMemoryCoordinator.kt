package com.myra.assistant.data.memory

import android.util.Log
import android.content.Context
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

enum class MemoryDecision { IGNORE, RECALL, SAVE, UPDATE, DELETE, TRANSIENT, NEEDS_CLARIFICATION, REJECT }
enum class MemoryRecallType { GENERAL, PREFERENCES, FRIENDS, BEST_FRIEND, LAST_TRANSACTION, EPISODES, GOALS, PROJECTS }
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

private data class MutationBatch(
    val lastId: String? = null,
    val deleted: Boolean = false,
    val transient: Boolean = false
)
private class MemoryMutationAbort(val reasonCode: MemoryFailureReason) : RuntimeException(reasonCode.name)

/** One final-turn owner. It is the only class allowed to authorize consolidated writes. */
class MemoryBrainCoordinator(
    private val store: AiriMemoryStore,
    private val reasoningProvider: MemoryReasoningProvider = UnavailableMemoryReasoningProvider,
    recoverOnInit: Boolean = true,
    private val wakeScheduler: AiriMemoryWakeScheduler = NoopAiriMemoryWakeScheduler
) {
    private val ownedSessions = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inactivityJobs = ConcurrentHashMap<String, Job>()
    private val segmentationRetryAttempts = ConcurrentHashMap<String, Int>()
    private val consolidationJobs = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val consolidationAttempts = ConcurrentHashMap<String, Int>()
    private val consolidationQueue = Channel<String>(capacity = 32)
    private val consolidationDrainScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reviewWorkerRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        backgroundScope.launch {
            for (episodeId in consolidationQueue) {
                try { consolidateEpisode(episodeId) }
                catch (error: Throwable) {
                    log("MEMORY_CONSOLIDATION_RETRY episode=${episodeId.hashCode()} reason=${error.javaClass.simpleName}")
                    consolidationJobs.remove(episodeId)
                    val attempt = consolidationAttempts.merge(episodeId, 1) { old, one -> old + one } ?: 1
                    store.retryBackgroundWork("CONSOLIDATION:$episodeId", attempt, error.javaClass.simpleName, System.currentTimeMillis())
                    wakeScheduler.wake(retryDelayMs(attempt))
                    continue
                }
                consolidationAttempts.remove(episodeId)
                consolidationJobs.remove(episodeId)
                store.completeBackgroundWork("CONSOLIDATION:$episodeId")
                scheduleConsolidationDrain()
            }
        }
        // A process can die after transcript commit but before the in-process
        // timeout fires. Recover only stale source-owned conversations; fresh
        // claims remain protected by the generation/claim guard below.
        if (recoverOnInit) backgroundScope.launch {
            recoverAbandonedConversations()
            store.reembedStale(32)
            runDurableBackgroundWork()
            store.unconsolidatedEpisodes(32).forEach { scheduleEpisodeConsolidation(it.episodeId) }
            schedulePendingReviews()
        }
    }

    suspend fun prepareFinalTurn(evidence: AuthoritativeMemoryTurnEvidence, staged: List<MemorySemanticFrame>, semanticConsistent: Boolean = true): FinalMemoryTurnPlan {
        if (ownedSessions.add(evidence.sessionId)) {
            AiriMemoryRuntime.beginSession(evidence.sessionId, evidence.turnId)
        } else {
            AiriMemoryRuntime.claimTurn(evidence.sessionId, evidence.turnId)
        }
        val bounded = staged.take(4)
        if (bounded.isEmpty()) return FinalMemoryTurnPlan(evidence.sourceText, decision = MemoryDecision.IGNORE)
        if (!semanticConsistent) return FinalMemoryTurnPlan(evidence.sourceText, decision = MemoryDecision.REJECT, rejectionReason = MemoryFailureReason.CRITICAL_LITERAL_MISSING.name)
        val isQuestion = bounded.any { it.intent == MemorySemanticIntent.RECALL }
        if (isQuestion && bounded.any { it.intent !in setOf(MemorySemanticIntent.RECALL, MemorySemanticIntent.CLARIFY) })
            return FinalMemoryTurnPlan(evidence.sourceText, decision = MemoryDecision.REJECT, rejectionReason = MemoryFailureReason.QUESTION_MUTATION.name)

        val resolved = mutableListOf<MemorySemanticFrame>()
        for (raw in bounded) {
            val contract = MemoryOperationContractValidator.validateAndRecover(raw, evidence)
            val structuralReason = contract.reason
            if (structuralReason != null) {
                log("MEMORY_CONSOLIDATION_PLAN turnId=${evidence.turnId} operation=${raw.intent} structural=$structuralReason authorized=false")
                return if (structuralReason in setOf(MemoryFailureReason.MISSING_REQUIRED_ENTITY, MemoryFailureReason.AMBIGUOUS_ENTITY))
                    clarify(evidence, structuralReason)
                else FinalMemoryTurnPlan(evidence.sourceText, decision = MemoryDecision.REJECT, rejectionReason = structuralReason.name)
            }
            val frame = contract.frame!!.copy(sourceSessionId = evidence.sessionId)
            if (contract.recoveredEntity) log(
                "MEMORY_ENTITY_RESOLUTION turnId=${evidence.turnId} operation=${frame.intent} " +
                    "source=CURRENT_FINAL_TURN candidateCount=1 resolved=true"
            )
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
            resolved.any { it.intent in setOf(MemorySemanticIntent.DELETE_ENTITY, MemorySemanticIntent.REMOVE_RELATIONSHIP, MemorySemanticIntent.INVALIDATE_FACT) } -> MemoryDecision.DELETE
            resolved.any { it.intent in setOf(MemorySemanticIntent.UPDATE_FACT, MemorySemanticIntent.SUPERSEDE_FACT, MemorySemanticIntent.UPDATE_GOAL, MemorySemanticIntent.RENAME_ENTITY, MemorySemanticIntent.REPLACE_RELATIONSHIP) } -> MemoryDecision.UPDATE
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
        val batch = try { store.transaction {
            var lastId: String? = null; var deleted = false; var transient = false
            for (frame in plan.operations) {
                if (frame.temporalScope == MemoryTemporalScope.TEMPORARY) {
                    transient = AiriWorkingMemory.transient(turn, frame.fact ?: plan.sourceText)
                    continue
                }
                when (frame.intent) {
                    MemorySemanticIntent.TRANSIENT_CONTEXT -> transient = AiriWorkingMemory.transient(turn, frame.fact ?: plan.sourceText)
                    MemorySemanticIntent.ADD_RELATIONSHIP -> {
                        val name = frame.person ?: throw MemoryMutationAbort(MemoryFailureReason.AMBIGUOUS_ENTITY)
                        val person = store.ensurePerson(name, turn.turnId)
                        val relationship = frame.relationship ?: throw MemoryMutationAbort(MemoryFailureReason.UNSUPPORTED_OPERATION)
                        val canonical = relationshipCanonicalFrame(frame, person, relationship, MemorySemanticIntent.ADD_FACT)
                        lastId = store.addSemantic(canonical, turn)
                            ?: throw MemoryMutationAbort(MemoryFailureReason.VERIFY_FAILED)
                        store.addRelationship(person.entityId, relationship, turn, frame.confidence)
                            ?: throw MemoryMutationAbort(MemoryFailureReason.VERIFY_FAILED)
                        store.recordConsolidationAction(canonical, turn, lastId)
                    }
                    MemorySemanticIntent.REMOVE_RELATIONSHIP -> {
                        val relationship = frame.relationship ?: throw MemoryMutationAbort(MemoryFailureReason.UNSUPPORTED_OPERATION)
                        deleted = store.endRelationship(frame.resolvedEntityId!!, relationship)
                        if (deleted) store.invalidateSemantic(relationshipSemanticKey(frame.resolvedEntityId))
                    }
                    MemorySemanticIntent.REPLACE_RELATIONSHIP -> {
                        val relationship = frame.replacementRelationship ?: throw MemoryMutationAbort(MemoryFailureReason.UNSUPPORTED_OPERATION)
                        val person = store.allPeople(500).singleOrNull { it.entityId == frame.resolvedEntityId }
                            ?: throw MemoryMutationAbort(MemoryFailureReason.TARGET_NOT_FOUND)
                        val canonical = relationshipCanonicalFrame(frame, person, relationship, MemorySemanticIntent.UPDATE_FACT)
                        lastId = store.addSemantic(canonical, turn)
                            ?: throw MemoryMutationAbort(MemoryFailureReason.VERIFY_FAILED)
                        store.addRelationship(person.entityId, relationship, turn, frame.confidence)
                            ?: throw MemoryMutationAbort(MemoryFailureReason.VERIFY_FAILED)
                        store.recordConsolidationAction(canonical, turn, lastId)
                    }
                    MemorySemanticIntent.RENAME_ENTITY -> {
                        val replacement = frame.replacementPerson ?: throw MemoryMutationAbort(MemoryFailureReason.AMBIGUOUS_ENTITY)
                        if (!store.renamePerson(frame.resolvedEntityId!!, replacement, turn.turnId))
                            throw MemoryMutationAbort(MemoryFailureReason.VERIFY_FAILED)
                        lastId = frame.resolvedEntityId
                    }
                    MemorySemanticIntent.DELETE_ENTITY -> deleted = store.deletePerson(frame.resolvedEntityId!!)
                    MemorySemanticIntent.ADD_EPISODE -> {
                        val ids = frame.episode?.participants.orEmpty().map { store.ensurePerson(it, turn.turnId).entityId }
                        lastId = store.addEpisode(frame, turn, ids)
                    }
                    MemorySemanticIntent.ADD_GOAL, MemorySemanticIntent.UPDATE_GOAL -> {
                        val canonical = goalCanonicalFrame(frame)
                        lastId = store.addSemantic(canonical, turn)
                            ?: throw MemoryMutationAbort(MemoryFailureReason.VERIFY_FAILED)
                        store.addGoal(canonical, turn)
                            ?: throw MemoryMutationAbort(MemoryFailureReason.VERIFY_FAILED)
                        store.recordConsolidationAction(canonical, turn, lastId)
                    }
                    MemorySemanticIntent.INVALIDATE_FACT -> {
                        deleted = frame.stableKey?.let { store.invalidateSemantic(it) } == true
                        if (deleted) store.recordConsolidationAction(frame, turn, null)
                    }
                    MemorySemanticIntent.ADD_FACT, MemorySemanticIntent.UPDATE_FACT,
                    MemorySemanticIntent.SUPERSEDE_FACT, MemorySemanticIntent.ADD_LINKED_FACT,
                    MemorySemanticIntent.ADD_IDEA, MemorySemanticIntent.ADD_PROJECT,
                    MemorySemanticIntent.ADD_SOLUTION, MemorySemanticIntent.ADD_WORKFLOW -> {
                        // The explicit fact fast path may run before multi-turn
                        // segmentation commits an episode. Never manufacture a
                        // one-turn episode ID; the committed span reconciles real
                        // provenance transactionally in linkEpisodeProvenance().
                        lastId = store.addSemantic(frame, turn)
                        if (lastId != null) store.recordConsolidationAction(frame, turn, lastId)
                    }
                    MemorySemanticIntent.RECALL, MemorySemanticIntent.CLARIFY, MemorySemanticIntent.NONE -> Unit
                }
                if (!transient && !deleted && frame.intent !in setOf(
                        MemorySemanticIntent.RECALL, MemorySemanticIntent.CLARIFY, MemorySemanticIntent.NONE
                    ) && lastId == null
                ) throw MemoryMutationAbort(MemoryFailureReason.VERIFY_FAILED)
            }
            MutationBatch(lastId, deleted, transient)
        } } catch (abort: MemoryMutationAbort) { return reject(turn.turnId, abort.reasonCode) }
        val lastId = batch.lastId; val deleted = batch.deleted; val transient = batch.transient
        val status = if (transient && lastId == null && !deleted) MemoryTransactionStatus.TRANSIENT else MemoryTransactionStatus.SUCCEEDED
        if (plan.operations.any { it.intent in SEMANTIC_CONSOLIDATION_INTENTS }) {
            AiriMemoryRuntime.markConsolidated(turn.sessionId, turn.turnId)
        }
        AiriWorkingMemory.record(VerifiedMemoryTransaction(turn.turnId, plan.operations.last().intent, status, lastId))
        return when { transient && lastId == null && !deleted -> MemoryBrainOutcome.Transient(); deleted -> MemoryBrainOutcome.Deleted(true); else -> MemoryBrainOutcome.Mutated(MemoryWriteResult.Saved(lastId!!)) }
    }

    suspend fun recall(query: String, limit: Int = 8, type: MemoryRecallType = MemoryRecallType.GENERAL): MemoryBrainOutcome.Recalled {
        val started = System.nanoTime()
        val answer = if (type == MemoryRecallType.LAST_TRANSACTION) AiriWorkingMemory.transactionAnswer() else null
        val rows = if (answer != null) emptyList() else store.retrieve(query, type, limit)
        if (rows.any { it.kind == "EPISODE" }) schedulePendingReviews()
        return MemoryBrainOutcome.Recalled(rows, answer, type, (System.nanoTime() - started) / 1_000_000)
    }

    /** Read facade for Memory Core; no persistence object escapes the owner. */
    suspend fun activeCards(limit: Int = 200): List<MemoryEntity> = store.activeCards(limit)

    suspend fun addFromManualUi(fact: String, category: MemoryCategory): MemoryWriteResult {
        val clean = fact.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 3..500) return MemoryWriteResult.Rejected("Memory must contain 3 to 500 characters.")
        val turn = System.currentTimeMillis(); val session = "manual-ui"
        AiriMemoryRuntime.claimTurn(session, turn)
        val evidence = AuthoritativeMemoryTurnEvidence(turn, clean, clean, sessionId = session)
        val frame = MemorySemanticFrame(MemorySemanticIntent.ADD_FACT, temporalScope = MemoryTemporalScope.CURRENT,
            fact = clean, category = category, stableKey = "manual:${category.name}:${AiriText.semanticKey(clean).take(48)}",
            confidence = 1.0, sourceSpan = clean, sourceTurnId = turn)
        val plan = prepareFinalTurn(evidence, listOf(frame))
        return ((executeFinalTurnPlan(plan, evidence) as? MemoryBrainOutcome.Mutated)?.result)
            ?: MemoryWriteResult.Rejected(plan.rejectionReason ?: "Memory write was not verified.")
    }

    suspend fun renameFromManualUi(entityId: String, replacement: String): Boolean {
        val clean = AiriText.displayName(replacement)
        if (clean.length !in 2..80 || clean.any(Char::isDigit)) return false
        return store.renamePerson(entityId, clean, System.currentTimeMillis())
    }

    suspend fun deleteMemory(card: MemoryEntity): Boolean = store.forgetCard(card)
    suspend fun clearMemories(): Boolean { store.clearAll(); return store.activeCards(1).isEmpty() }
    suspend fun recordBehaviorObservation(signal: BehaviorSignal): MemoryWriteResult? = BehaviorMemoryLearner(store).observe(signal)
    suspend fun reviewInferredMemory(now: Long) = BehaviorMemoryLearner(store).decay(now)

    fun scheduleNeuralReindex() {
        backgroundScope.launch {
            store.enqueueBackgroundWork("REINDEX", "e5")
            wakeScheduler.wake()
            if (!store.neuralEmbeddingReady()) {
                // A cold process must keep its feature-hash rows intact.  The
                // durable token is retried after E5 is ready instead of being
                // falsely completed with a fallback snapshot.
                store.retryBackgroundWork("REINDEX:e5", 1, "NEURAL_NOT_READY", System.currentTimeMillis())
                wakeScheduler.wake(retryDelayMs(1))
                return@launch
            }
            var changed: Int
            do { changed = store.reembedStale(16) } while (changed > 0)
            store.completeBackgroundWork("REINDEX:e5")
        }
    }

    suspend fun captureConversation(evidence: AuthoritativeMemoryTurnEvidence, assistantText: String?) {
        appendConversationTruth(evidence, assistantText)
        backgroundScope.launch { segmentCommittedConversation(evidence.sessionId, eof = false) }
        scheduleInactivityClose(evidence.sessionId)
    }

    internal suspend fun appendConversationTruth(evidence: AuthoritativeMemoryTurnEvidence, assistantText: String?) {
        val now = System.currentTimeMillis(); val base = evidence.turnId * 2
        store.appendConversation(ConversationTruthEntity("${evidence.utteranceId}:user", evidence.sessionId, base,
            evidence.turnId, evidence.utteranceId, "user", evidence.canonicalText, now,
            rawText = evidence.canonicalText, canonicalText = evidence.canonicalText,
            displayText = evidence.displayText, source = "FINAL_USER_TURN", finalized = true,
            provenanceMetadata = (evidence.protectedCanonicalNames + evidence.protectedDisplayNames)
                .distinct().joinToString("\u001F").takeIf(String::isNotEmpty)))
        assistantText?.takeIf(String::isNotBlank)?.let { store.appendConversation(ConversationTruthEntity(
            "${evidence.utteranceId}:assistant", evidence.sessionId, base + 1, evidence.turnId,
            evidence.utteranceId, "assistant", it, now, source = "VERIFIED_RESPONSE", finalized = true)) }
    }

    /** Source-owned lifecycle close. Safe to call while the voice scope is stopping. */
    fun closeConversation(conversationId: String, reason: String) {
        inactivityJobs.remove(conversationId)?.cancel()
        backgroundScope.launch { flushConversation(conversationId, reason) }
    }

    suspend fun flushConversation(conversationId: String, reason: String): Int {
        inactivityJobs.remove(conversationId)?.cancel()
        val committed = segmentCommittedConversation(conversationId, eof = true)
        store.segmentationState(conversationId)?.let { state ->
            store.saveSegmentationState(state.copy(
                eofIdentified = true, conversationStatus = "CLOSED",
                eofReason = reason, lastActivityAt = System.currentTimeMillis()
            ))
        }
        return committed
    }

    private fun scheduleInactivityClose(conversationId: String) {
        inactivityJobs.remove(conversationId)?.cancel()
        inactivityJobs[conversationId] = backgroundScope.launch {
            delay(CONVERSATION_INACTIVITY_EOF_MS)
            inactivityJobs.remove(conversationId)
            flushConversation(conversationId, "INACTIVITY_TIMEOUT")
        }
    }

    suspend fun recoverAbandonedConversations(now: Long = System.currentTimeMillis()): Int {
        val abandoned = store.abandonedSegmentationStates(now - CONVERSATION_INACTIVITY_EOF_MS, 16)
        return abandoned.sumOf { state -> flushConversation(state.conversationId, "PROCESS_RECOVERY") }
    }

    /**
     * Stateful Plast-Mem claim/commit/abort equivalent. It runs from the service
     * coroutine after response ownership is settled, never on the audio callback.
     */
    suspend fun segmentCommittedConversation(conversationId: String, eof: Boolean): Int {
        val now = System.currentTimeMillis()
        val old = store.segmentationState(conversationId)
            ?: SegmentationStateEntity(conversationId, -1, eof, 0, lastActivityAt = now)
        val staleClaim = old.activeSince?.let { now - it > SEGMENT_CLAIM_TIMEOUT_MS } == true
        if (old.claimId != null && !staleClaim) return 0
        val end = store.lastConversationSequence(conversationId) ?: return 0
        if (old.nextSegmentStartSequence > end) return 0
        val claim = UUID.randomUUID().toString()
        store.saveSegmentationState(old.copy(
            lastMessageSequence = end, eofIdentified = eof,
            activeSegmentStartSequence = old.nextSegmentStartSequence,
            activeSegmentEndSequence = end, activeSince = now, claimId = claim,
            generation = old.generation + 1, conversationStatus = if (eof) "CLOSING" else "ACTIVE",
            eofReason = old.eofReason, lastActivityAt = now
        ))
        return runCatching {
            val messages = store.conversationRange(conversationId, old.nextSegmentStartSequence, end)
            val plan = AiriActiveSegmentationPipeline.plan(messages, eof, reasoningProvider)
            var committed = 0
            plan.finalized.forEach { range ->
                val segmentMessages = messages.filter { it.sequence in range }
                val classification = plan.classifications[range] ?: SegmentClassification.INFORMATIVE
                val reason = when {
                    plan.boundaries.any { it.afterSequence == range.last && it.hard } -> "HARD_TIME_GAP"
                    plan.boundaries.any { it.afterSequence == range.last } ->
                        plan.boundaries.first { it.afterSequence == range.last }.reason.name
                    eof -> "CONVERSATION_EOF"
                    else -> "INFORMATIVE_BOUNDARY"
                }
                val spanId = "span:$conversationId:${range.first}:${range.last}"
                val span = EpisodeSpanEntity(spanId, conversationId, range.first, range.last,
                    classification.name, reason, now)
                val inserted = store.saveEpisodeSpan(span)
                val episodeId = store.ensureEpisodeForSpan(span, segmentMessages)
                val sourceTurns = segmentMessages.filter { it.role == "user" }.map { it.turnId }.distinct()
                if (episodeId != null && sourceTurns.isNotEmpty() &&
                    sourceTurns.all { AiriMemoryRuntime.wasConsolidated(conversationId, it) }) {
                    // The structured final-turn actions have already been verified. Link
                    // their durable facts to the committed episode before declaring the
                    // episode consolidated; a span alone is never semantic truth.
                    store.linkEpisodeProvenance(episodeId, conversationId, range.first, range.last)
                    if (reasoningProvider === UnavailableMemoryReasoningProvider) {
                        store.markEpisodeConsolidated(episodeId, now)
                    }
                }
                if (episodeId != null && reasoningProvider !== UnavailableMemoryReasoningProvider) {
                    scheduleEpisodeConsolidation(episodeId)
                }
                if (inserted) committed++
            }
            val next = plan.carriedTail?.first ?: (plan.finalized.lastOrNull()?.last?.plus(1) ?: old.nextSegmentStartSequence)
            store.saveSegmentationState(SegmentationStateEntity(
                conversationId, end, eof, next, generation = old.generation + 1,
                conversationStatus = if (eof) "CLOSED" else "ACTIVE",
                eofReason = old.eofReason, lastActivityAt = now
            ))
            segmentationRetryAttempts.remove(conversationId)
            committed
        }.getOrElse {
            store.saveSegmentationState(old.copy(
                lastMessageSequence = end, eofIdentified = eof,
                activeSegmentStartSequence = null, activeSegmentEndSequence = null,
                activeSince = null, claimId = null, generation = old.generation + 1
            ))
            val attempt = segmentationRetryAttempts.merge(conversationId, 1) { prior, one -> prior + one } ?: 1
            if (attempt < 3) backgroundScope.launch {
                delay(attempt * 2_000L)
                segmentCommittedConversation(conversationId, eof)
            }
            0
        }
    }

    suspend fun reviewEpisodes(conversationId: String, ratings: Map<String, EpisodeReviewRating>, reviewedAt: Long): Int =
        store.reviewEpisodes(conversationId, ratings, reviewedAt)

    private fun scheduleEpisodeConsolidation(episodeId: String) {
        if (!consolidationJobs.add(episodeId)) return
        backgroundScope.launch {
            store.enqueueBackgroundWork("CONSOLIDATION", episodeId)
            wakeScheduler.wake()
            if (consolidationQueue.trySend(episodeId).isFailure) {
                consolidationJobs.remove(episodeId)
                log("MEMORY_CONSOLIDATION_BACKPRESSURE episode=${episodeId.hashCode()}")
                store.retryBackgroundWork("CONSOLIDATION:$episodeId", 1, "CHANNEL_BACKPRESSURE", System.currentTimeMillis())
                wakeScheduler.wake(retryDelayMs(1))
                scheduleConsolidationDrain()
            }
        }
    }

    private fun scheduleConsolidationDrain() {
        if (!consolidationDrainScheduled.compareAndSet(false, true)) return
        backgroundScope.launch {
            delay(1_000L)
            try { store.unconsolidatedEpisodes(32).forEach { scheduleEpisodeConsolidation(it.episodeId) } }
            finally { consolidationDrainScheduled.set(false) }
        }
    }

    /** Called by the WorkManager wake layer; it dispatches, never mutates directly. */
    suspend fun runDurableBackgroundWork(now: Long = System.currentTimeMillis()): Int {
        var dispatched = 0
        store.dueBackgroundWork(now, 32).forEach { work ->
            if (!store.claimBackgroundWork(work.workId, now)) return@forEach
            when (work.kind) {
                "CONSOLIDATION" -> {
                    consolidationJobs.remove(work.subjectId)
                    scheduleEpisodeConsolidation(work.subjectId); dispatched++
                }
                "REVIEW" -> {
                    // Recreate a fresh claim in the review lane; the wake work
                    // itself is only a scheduler token, never a second owner.
                    store.completeBackgroundWork(work.workId); schedulePendingReviews(); dispatched++
                }
                "REINDEX" -> {
                    if (store.neuralEmbeddingReady()) {
                        scheduleNeuralReindex(); dispatched++
                    } else {
                        store.retryBackgroundWork(work.workId, work.attemptCount + 1, "NEURAL_NOT_READY", now)
                        wakeScheduler.wake(retryDelayMs(work.attemptCount + 1))
                    }
                }
                else -> store.completeBackgroundWork(work.workId)
            }
        }
        return dispatched
    }

    internal suspend fun consolidateEpisode(episodeId: String): Int {
        val episode = store.unconsolidatedEpisodes(32).firstOrNull { it.episodeId == episodeId } ?: return 0
        val messages = store.episodeMessages(episode)
        val candidates = store.semanticCandidatesForEpisode(episode.conversationId,
            "${episode.title} ${episode.content}", 20)
        val supplied = candidates.map { ConsolidationCandidate(it.memoryId, it.statement, it.category) }
        val predictStarted = System.nanoTime()
        val prediction = if (supplied.isEmpty()) null else reasoningProvider.predict(episode.title, supplied).takeIf(String::isNotBlank)
        val predictMs = (System.nanoTime() - predictStarted) / 1_000_000
        val actions = reasoningProvider.calibrate(episode.title, episode.content, prediction, supplied, messages).take(20)
        if (actions.isEmpty()) {
            store.markEpisodeConsolidated(episodeId, System.currentTimeMillis())
            log("MEMORY_CONSOLIDATION_RESULT episode=${episodeId.hashCode()} actions=0 predictMs=$predictMs verified=true")
            return 0
        }
        val candidateById = candidates.associateBy { it.memoryId }
        val userMessages = messages.filter { it.role == "user" }
        val last = userMessages.lastOrNull() ?: return 0
        val canonicalEvidence = userMessages.joinToString("\n") { it.canonicalText }
        val displayEvidence = userMessages.joinToString("\n") { it.displayText }
        val protectedNames = userMessages.flatMap { it.provenanceMetadata?.split('\u001F').orEmpty() }
            .filter(String::isNotBlank).distinct()
        val evidence = AuthoritativeMemoryTurnEvidence(last.turnId, canonicalEvidence, displayEvidence,
            protectedNames, protectedNames, episode.conversationId, last.utteranceId)
        var applied = 0
        store.transaction {
            actions.forEach { action ->
                if (action.confidence < .65) return@forEach
                if (action.assertionMode != "USER_ASSERTED") return@forEach
                val category = action.category.toMemoryCategory() ?: return@forEach
                val target = action.targetFactId?.let(candidateById::get)
                if (action.kind != SemanticConsolidationAction.NEW && target == null) return@forEach
                val fact = action.fact.trim()
                if (action.kind !in setOf(SemanticConsolidationAction.INVALIDATE, SemanticConsolidationAction.REINFORCE) && fact.length !in 3..500) return@forEach
                if (!actionEvidenceSupported(action, userMessages)) return@forEach
                if (!criticalLiteralsSupported(action, evidence, target)) return@forEach
                val frame = MemorySemanticFrame(
                    intent = when (action.kind) {
                        SemanticConsolidationAction.NEW -> MemorySemanticIntent.ADD_FACT
                        SemanticConsolidationAction.REINFORCE -> MemorySemanticIntent.ADD_FACT
                        SemanticConsolidationAction.UPDATE -> MemorySemanticIntent.UPDATE_FACT
                        SemanticConsolidationAction.INVALIDATE -> MemorySemanticIntent.INVALIDATE_FACT
                    }, fact = fact.takeIf(String::isNotBlank) ?: target?.statement,
                    category = category, stableKey = target?.semanticKey
                        ?: "pc:${category.name}:${AiriText.semanticKey(fact).take(64)}",
                    confidence = action.confidence, sourceSpan = episode.content,
                    sourceTurnId = last.turnId, sourceSessionId = episode.conversationId,
                    sourceEpisodeIds = listOf(episodeId)
                )
                if (AiriMemorySafetyPolicy.rejectReason(frame, evidence, false) != null) return@forEach
                if (category == MemoryCategory.PERSON) {
                    val personName = action.person ?: return@forEach
                    val relationship = action.relationship?.let { runCatching { PersonRelationship.valueOf(it) }.getOrNull() }
                        ?: return@forEach
                    val matches = store.peopleByName(personName)
                    val person = when (action.kind) {
                        SemanticConsolidationAction.NEW -> store.ensurePerson(personName, last.turnId)
                        else -> matches.singleOrNull() ?: return@forEach
                    }
                    val relationshipFrame = relationshipCanonicalFrame(frame.copy(
                        stableKey = target?.semanticKey ?: relationshipSemanticKey(person.entityId),
                        resolvedEntityId = person.entityId, person = personName, relationship = relationship
                    ), person, relationship, if (action.kind == SemanticConsolidationAction.UPDATE)
                        MemorySemanticIntent.UPDATE_FACT else MemorySemanticIntent.ADD_FACT)
                    when (action.kind) {
                        SemanticConsolidationAction.NEW, SemanticConsolidationAction.UPDATE -> {
                            val id = store.addSemantic(relationshipFrame, evidence) ?: return@forEach
                            store.addRelationship(person.entityId, relationship, evidence, action.confidence) ?: return@forEach
                            store.linkSemanticProvenance(id, episodeId)
                            store.recordConsolidationAction(relationshipFrame, evidence, id); applied++
                        }
                        SemanticConsolidationAction.REINFORCE -> if (store.reinforceSemantic(target!!.memoryId, episodeId, .03)) applied++
                        SemanticConsolidationAction.INVALIDATE -> if (store.invalidateSemantic(target!!.semanticKey)) {
                            store.endRelationship(person.entityId, relationship)
                            store.linkSemanticProvenance(target.memoryId, episodeId); applied++
                        }
                    }
                    return@forEach
                }
                if (category == MemoryCategory.GOAL) {
                    val title = action.goalTitle ?: target?.statement ?: return@forEach
                    val goalFrame = frame.copy(
                        intent = if (action.kind == SemanticConsolidationAction.UPDATE) MemorySemanticIntent.UPDATE_GOAL else MemorySemanticIntent.ADD_GOAL,
                        stableKey = target?.semanticKey ?: "goal:${AiriText.semanticKey(title)}",
                        goal = GoalMemoryPayload(title, fact.takeIf(String::isNotBlank) ?: title,
                            status = if (action.kind == SemanticConsolidationAction.INVALIDATE) "COMPLETED" else "ACTIVE")
                    )
                    when (action.kind) {
                        SemanticConsolidationAction.NEW, SemanticConsolidationAction.UPDATE -> {
                            val canonical = goalCanonicalFrame(goalFrame)
                            val id = store.addSemantic(canonical, evidence) ?: return@forEach
                            store.addGoal(canonical, evidence) ?: return@forEach
                            store.linkSemanticProvenance(id, episodeId)
                            store.recordConsolidationAction(canonical, evidence, id); applied++
                        }
                        SemanticConsolidationAction.REINFORCE -> if (store.reinforceSemantic(target!!.memoryId, episodeId, .03)) applied++
                        SemanticConsolidationAction.INVALIDATE -> if (store.invalidateSemantic(target!!.semanticKey)) {
                            store.closeGoal(target.semanticKey, "COMPLETED")
                            store.linkSemanticProvenance(target.memoryId, episodeId); applied++
                        }
                    }
                    return@forEach
                }
                when (action.kind) {
                    SemanticConsolidationAction.REINFORCE -> if (store.reinforceSemantic(target!!.memoryId, episodeId, .03)) applied++
                    SemanticConsolidationAction.INVALIDATE -> if (store.invalidateSemantic(target!!.semanticKey)) {
                        store.linkSemanticProvenance(target.memoryId, episodeId); applied++
                    }
                    SemanticConsolidationAction.NEW -> {
                        val duplicate = store.nearEquivalentSemantic(fact, category.name)
                        if (duplicate != null) {
                            if (store.reinforceSemantic(duplicate.memoryId, episodeId, .03)) applied++
                        } else store.addSemantic(frame, evidence)?.let { id ->
                            store.linkSemanticProvenance(id, episodeId); store.recordConsolidationAction(frame, evidence, id); applied++
                        }
                    }
                    SemanticConsolidationAction.UPDATE -> store.addSemantic(frame, evidence)?.let { id ->
                        store.linkSemanticProvenance(id, episodeId); store.recordConsolidationAction(frame, evidence, id); applied++
                    }
                }
            }
            check(store.markEpisodeConsolidated(episodeId, System.currentTimeMillis())) { "episode consolidation marker failed" }
        }
        log("MEMORY_CONSOLIDATION_RESULT episode=${episodeId.hashCode()} actions=$applied predictMs=$predictMs verified=true")
        return applied
    }

    private fun schedulePendingReviews() {
        if (!reviewWorkerRunning.compareAndSet(false, true)) return
        backgroundScope.launch {
            try {
                store.pendingReviewConversations(16).forEach { conversationId ->
                    val workId = "REVIEW:$conversationId"
                    store.enqueueBackgroundWork("REVIEW", conversationId)
                    if (!store.claimBackgroundWork(workId, System.currentTimeMillis())) return@forEach
                    try {
                        val pending = store.pendingReviews(conversationId, 64)
                        val queryMap = linkedMapOf<String, MutableList<String>>()
                        pending.forEach { row -> row.episodeIds.split(',').filter(String::isNotBlank).forEach { id ->
                            queryMap.getOrPut(id) { mutableListOf() }.add(row.matchedQuery)
                        } }
                        val episodes = store.episodeCards(queryMap.keys.toList())
                        val context = store.promptProjection(conversationId, 32)
                        val ratings = reasoningProvider.rateEpisodes(context, episodes, queryMap)
                        if (ratings.isNotEmpty()) {
                            store.reviewEpisodes(conversationId, ratings, System.currentTimeMillis())
                            val completedEpisodes = ratings.keys
                            val completedReviews = pending.filter { row ->
                                row.episodeIds.split(',').filter(String::isNotBlank).all(completedEpisodes::contains)
                            }.map { it.reviewId }
                            if (completedReviews.isNotEmpty()) store.deletePendingReviews(completedReviews)
                        }
                        if (store.pendingReviews(conversationId, 1).isEmpty()) store.completeBackgroundWork(workId)
                        else {
                            store.retryBackgroundWork(workId, 1, "NO_RATINGS", System.currentTimeMillis())
                            wakeScheduler.wake(retryDelayMs(1))
                        }
                    } catch (error: Throwable) {
                        store.retryBackgroundWork(workId, 1, error.javaClass.simpleName, System.currentTimeMillis())
                        wakeScheduler.wake(retryDelayMs(1)); throw error
                    }
                }
            } catch (error: Throwable) {
                log("MEMORY_REVIEW_RETRY reason=${error.javaClass.simpleName}")
            } finally { reviewWorkerRunning.set(false) }
        }
    }

    suspend fun traceSpark(eventId: String, parentEventId: String?, source: String, lane: String, kind: String, outcome: String) =
        store.appendSparkTrace(SparkTraceEntity(UUID.randomUUID().toString(), eventId, parentEventId,
            source, lane, kind, outcome, System.currentTimeMillis()))

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
    private fun retryDelayMs(attempt: Int) = (1L shl attempt.coerceIn(0, 8)) * 30_000L

    private fun String.toMemoryCategory(): MemoryCategory? = when (uppercase()) {
        "IDENTITY", "PERSONALITY" -> MemoryCategory.IDENTITY
        "PREFERENCE" -> MemoryCategory.PREFERENCE
        "INTEREST" -> MemoryCategory.CURRENT_INTEREST
        "RELATIONSHIP" -> MemoryCategory.PERSON
        "EXPERIENCE" -> MemoryCategory.LIFE_EVENT
        "GOAL" -> MemoryCategory.GOAL
        "GUIDELINE" -> MemoryCategory.WORKFLOW
        else -> null
    }

    private fun criticalLiteralsSupported(action: EpisodeSemanticAction, evidence: AuthoritativeMemoryTurnEvidence,
        target: SemanticMemoryEntity?): Boolean {
        val structured = listOf(action.fact, action.person.orEmpty(), action.goalTitle.orEmpty()) + action.criticalLiterals
        val exact = Regex("(?<![\\p{L}\\p{N}])(?:\\d[\\d.,:/-]*|[A-Z][A-Z0-9_-]{2,})(?![\\p{L}\\p{N}])")
            .findAll(structured.joinToString(" ")).map { it.value }.toSet()
        val proper = Regex("(?<![\\p{L}])\\p{Lu}[\\p{Ll}]{2,}(?:[ -]\\p{Lu}[\\p{Ll}]{2,})*")
            .findAll(structured.joinToString(" ")).map { it.value }.filterNot { it in ROLE_LABELS }.toSet()
        val declared = action.criticalLiterals.map(String::trim).filter(String::isNotEmpty).toSet()
        val required = exact + proper + declared + listOfNotNull(action.person, action.goalTitle)
        return required.all { literal ->
            if (literal.any(Char::isDigit)) evidence.variants.any { variant ->
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(literal)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(variant)
            } else FinalTurnSourceSpanAuthorizer.groundedLiteral(literal, evidence) ||
                target?.statement?.contains(literal, ignoreCase = true) == true
        }
    }

    private fun actionEvidenceSupported(action: EpisodeSemanticAction, userMessages: List<ConversationTruthEntity>): Boolean {
        if (action.sourceMessageSequences.isEmpty() || action.sourceSpans.isEmpty()) return false
        val supported = userMessages.filter { it.sequence in action.sourceMessageSequences }
        if (supported.size != action.sourceMessageSequences.distinct().size) return false
        return action.sourceSpans.all { span -> supported.any { message ->
            FinalTurnSourceSpanAuthorizer.groundedSpan(span, message.canonicalText, message.displayText)
        } }
    }

    /** Structured rows are projections of these canonical semantic facts. */
    private fun relationshipSemanticKey(entityId: String) = "relationship:$entityId"
    private fun relationshipCanonicalFrame(source: MemorySemanticFrame, person: PersonEntity,
        relationship: PersonRelationship, intent: MemorySemanticIntent) = source.copy(
        intent = intent,
        fact = "${person.canonicalName} is Zopy's ${relationship.name.lowercase().replace('_', ' ')}",
        category = MemoryCategory.PERSON,
        stableKey = relationshipSemanticKey(person.entityId),
        resolvedEntityId = person.entityId
    )
    private fun goalCanonicalFrame(source: MemorySemanticFrame): MemorySemanticFrame {
        val goal = source.goal ?: throw MemoryMutationAbort(MemoryFailureReason.MISSING_REQUIRED_GOAL)
        val key = source.stableKey?.takeIf(String::isNotBlank) ?: "goal:${AiriText.semanticKey(goal.title)}"
        return source.copy(
            intent = if (source.intent == MemorySemanticIntent.UPDATE_GOAL) MemorySemanticIntent.UPDATE_FACT else MemorySemanticIntent.ADD_FACT,
            fact = goal.description?.takeIf(String::isNotBlank) ?: goal.title,
            category = MemoryCategory.GOAL,
            stableKey = key,
            goal = goal
        )
    }

    companion object {
        private const val SEGMENT_CLAIM_TIMEOUT_MS = 15L * 60L * 1000L
        private const val CONVERSATION_INACTIVITY_EOF_MS = 30L * 60L * 1000L
        private val ROLE_LABELS = setOf("Speaker", "User", "Assistant", "Zopy", "Lyra")
        private val SEMANTIC_CONSOLIDATION_INTENTS = setOf(
            MemorySemanticIntent.ADD_FACT, MemorySemanticIntent.ADD_LINKED_FACT,
            MemorySemanticIntent.UPDATE_FACT, MemorySemanticIntent.SUPERSEDE_FACT,
            MemorySemanticIntent.INVALIDATE_FACT, MemorySemanticIntent.ADD_IDEA,
            MemorySemanticIntent.ADD_PROJECT, MemorySemanticIntent.ADD_SOLUTION,
            MemorySemanticIntent.ADD_WORKFLOW
        )
        @Volatile private var shared: MemoryBrainCoordinator? = null
        fun get(context: Context): MemoryBrainCoordinator = shared ?: synchronized(this) {
            shared ?: run {
                val holder = arrayOfNulls<MemoryBrainCoordinator>(1)
                val provider = AndroidE5EmbeddingProvider(context.applicationContext) {
                    holder[0]?.scheduleNeuralReindex()
                }
                MemoryBrainCoordinator(RoomAiriMemoryStore(
                    LyraMemoryDatabase.get(context.applicationContext), embeddingProvider = provider
                ), GeminiMemoryReasoningProvider(context.applicationContext), wakeScheduler = WorkManagerAiriMemoryWakeScheduler(context.applicationContext)).also { holder[0] = it; shared = it }
            }
        }
    }
}
