package com.myra.assistant.agent

import com.myra.assistant.data.memory.ContextEntry
import com.myra.assistant.data.memory.ContextMutation
import com.myra.assistant.data.memory.LyraContextRegistry
import java.util.UUID

enum class SparkIntent { PLAN, PROPOSAL, ACTION, PAUSE, RESUME, REROUTE, CONTEXT }
enum class SparkPriority { CRITICAL, HIGH, NORMAL, LOW }
enum class SparkInterrupt { FORCE, SOFT, NONE }
enum class SparkGuidanceType { PROPOSAL, INSTRUCTION, MEMORY_RECALL }
enum class SparkRisk { HIGH, MEDIUM, LOW, NONE }
enum class SparkPersonaStrength { VERY_HIGH, HIGH, MEDIUM, LOW, VERY_LOW }
enum class SparkUrgency { IMMEDIATE, SOON, LATER }

data class SparkGuidanceOption(
    val label: String, val steps: List<String>, val rationale: String? = null,
    val possibleOutcomes: List<String> = emptyList(), val risk: SparkRisk = SparkRisk.NONE,
    val fallback: List<String> = emptyList(), val triggers: List<String> = emptyList()
)
data class SparkGuidance(
    val type: SparkGuidanceType, val persona: Map<String, SparkPersonaStrength> = emptyMap(),
    val options: List<SparkGuidanceOption>
)
sealed interface SparkContextDestinations {
    data object All : SparkContextDestinations
    data class ListOf(val values: List<String>) : SparkContextDestinations
    data class Filter(val include: List<String> = emptyList(), val exclude: List<String> = emptyList()) : SparkContextDestinations
}
data class SparkContextPatch(
    val source: String, val lane: String, val strategy: ContextMutation,
    val text: String, val generation: Long, val ideas: List<String> = emptyList(),
    val hints: List<String> = emptyList(), val destinations: SparkContextDestinations? = null,
    val metadata: Map<String, Any?> = emptyMap()
)
data class SparkCommand(
    val commandId: String = UUID.randomUUID().toString(), val eventId: String,
    val parentEventId: String? = null, val destinations: List<String>,
    val interrupt: SparkInterrupt = SparkInterrupt.NONE, val priority: SparkPriority = SparkPriority.NORMAL,
    val intent: SparkIntent = SparkIntent.ACTION, val requiresAck: Boolean = false,
    val ack: String? = null, val guidance: SparkGuidance? = null,
    val contexts: List<SparkContextPatch> = emptyList()
)

data class SparkNotifyEvent(
    val eventId: String = UUID.randomUUID().toString(), val parentEventId: String? = null,
    val source: String, val lane: String, val headline: String,
    val priority: SparkPriority = SparkPriority.NORMAL, val destinations: List<String> = listOf("character"),
    val urgency: SparkUrgency = SparkUrgency.SOON, val payload: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

data class SparkNotifyResponseControl(
    val forceResponse: Boolean = false,
    val forceTextResponse: Boolean = false,
    val forceSparkCommandResponse: Boolean = false,
    val appendSystemInstructions: List<String> = emptyList(),
    val appendUserSections: List<String> = emptyList(),
    val replaceUserMessage: String? = null
)

sealed interface SparkNotifyDecision {
    data object NoResponse : SparkNotifyDecision
    data class TextReaction(val text: String) : SparkNotifyDecision
    data class Commands(val values: List<SparkCommand>) : SparkNotifyDecision
}

data class SparkDispatchResult(val accepted: Boolean, val stale: Boolean = false, val reason: String? = null)

/**
 * Android-equivalent port of AIRI Spark Command and Spark Notify contracts.
 * It routes typed events; it does not create an autonomous second agent and it
 * cannot write durable memory. Memory-recall guidance is resolved by LYRA's one
 * memory owner before a downstream consumer receives context.
 */
class LyraSparkRuntime(
    private val contexts: LyraContextRegistry,
    private val commandSink: (SparkCommand) -> Unit,
    private val trace: (eventId: String, parentEventId: String?, source: String, lane: String, kind: String, outcome: String) -> Unit = { _, _, _, _, _, _ -> }
) {
    private val latestGeneration = linkedMapOf<String, Long>()

    @Synchronized fun dispatch(command: SparkCommand): SparkDispatchResult {
        if (command.destinations.isEmpty()) return SparkDispatchResult(false, reason = "MISSING_DESTINATION")
        command.contexts.forEach { patch ->
            if (!patch.appliesTo(command.destinations)) return@forEach
            val key = "${patch.source}:${patch.lane}"
            val current = latestGeneration[key] ?: Long.MIN_VALUE
            if (patch.generation < current) {
                trace(command.eventId, command.parentEventId, "spark-command", patch.lane, "STALE", "IGNORED")
                return SparkDispatchResult(false, stale = true, reason = "STALE_CONTEXT")
            }
            latestGeneration[key] = patch.generation
            val value = buildList {
                add(patch.text)
                patch.ideas.take(8).forEach { add("idea: $it") }
                patch.hints.take(8).forEach { add("hint: $it") }
            }.joinToString("\n")
            contexts.ingest(ContextEntry(key, value, 0, patch.generation,
                metadata = patch.metadata.mapValues { it.value?.toString().orEmpty() }), patch.strategy)
        }
        commandSink(command)
        trace(command.eventId, command.parentEventId, "spark-command", command.destinations.joinToString(","), command.intent.name, "DISPATCHED")
        return SparkDispatchResult(true)
    }

    fun notify(
        event: SparkNotifyEvent,
        control: SparkNotifyResponseControl = SparkNotifyResponseControl(),
        policy: (SparkNotifyEvent) -> SparkNotifyDecision
    ): SparkNotifyDecision {
        val proposed = policy(event)
        val decision = when {
            control.forceTextResponse && proposed !is SparkNotifyDecision.TextReaction -> SparkNotifyDecision.NoResponse
            control.forceSparkCommandResponse && proposed !is SparkNotifyDecision.Commands -> SparkNotifyDecision.NoResponse
            control.forceResponse && proposed is SparkNotifyDecision.NoResponse -> SparkNotifyDecision.NoResponse
            else -> proposed
        }
        when (decision) {
            SparkNotifyDecision.NoResponse -> trace(event.eventId, event.parentEventId, event.source, event.lane, "NOTIFY", "NO_RESPONSE")
            is SparkNotifyDecision.TextReaction -> trace(event.eventId, event.parentEventId, event.source, event.lane, "NOTIFY", "TEXT_REACTION")
            is SparkNotifyDecision.Commands -> {
                decision.values.forEach { draft -> dispatch(draft.copy(eventId = draft.eventId.ifBlank { event.eventId }, parentEventId = draft.parentEventId ?: event.eventId)) }
                trace(event.eventId, event.parentEventId, event.source, event.lane, "NOTIFY", "COMMANDS:${decision.values.size}")
            }
        }
        return decision
    }

    private fun SparkContextPatch.appliesTo(commandDestinations: List<String>): Boolean = when (val routing = destinations) {
        null, SparkContextDestinations.All -> true
        is SparkContextDestinations.ListOf -> routing.values.any { it in commandDestinations }
        is SparkContextDestinations.Filter ->
            (routing.include.isEmpty() || routing.include.any { it in commandDestinations }) &&
                routing.exclude.none { it in commandDestinations }
    }
}

private data class ScheduledSparkNotify(
    val event: SparkNotifyEvent,
    val control: SparkNotifyResponseControl,
    val nextRunAt: Long,
    val attempts: Int,
    val maxAttempts: Int
)

/**
 * Android-equivalent of AIRI's character-orchestrator attention queue. Hosts
 * call tick from their lifecycle scheduler; the queue itself is deterministic,
 * bounded, deduplicated, and independently testable.
 */
class SparkNotifyScheduler(
    private val runtime: LyraSparkRuntime,
    private val clock: () -> Long = System::currentTimeMillis,
    private val requeueDelayMs: Long = 30_000,
    private val maxAttempts: Int = 3,
    private val capacity: Int = 64
) {
    private val scheduled = mutableListOf<ScheduledSparkNotify>()

    @Synchronized fun enqueue(event: SparkNotifyEvent, control: SparkNotifyResponseControl = SparkNotifyResponseControl()): Boolean {
        if (scheduled.any { it.event.eventId == event.eventId }) return false
        if (scheduled.size >= capacity) scheduled.removeAt(0)
        scheduled += ScheduledSparkNotify(event, control, nextRun(event, 0), 0, maxAttempts)
        return true
    }

    @Synchronized fun pendingCount() = scheduled.size

    fun handleIncoming(
        event: SparkNotifyEvent,
        control: SparkNotifyResponseControl = SparkNotifyResponseControl(),
        policy: (SparkNotifyEvent) -> SparkNotifyDecision
    ): SparkNotifyDecision? = if (event.urgency == SparkUrgency.IMMEDIATE) {
        runtime.notify(event, control, policy)
    } else {
        enqueue(event, control); null
    }

    fun tick(policy: (SparkNotifyEvent) -> SparkNotifyDecision): SparkNotifyDecision? {
        val item = synchronized(this) {
            val index = scheduled.indexOfFirst { it.nextRunAt <= clock() }
            if (index < 0) null else scheduled.removeAt(index)
        } ?: return null
        return runCatching { runtime.notify(item.event, item.control, policy) }.getOrElse {
            if (item.attempts + 1 < item.maxAttempts) synchronized(this) {
                scheduled += item.copy(
                    attempts = item.attempts + 1,
                    nextRunAt = nextRun(item.event, item.attempts + 1)
                )
            }
            null
        }
    }

    private fun nextRun(event: SparkNotifyEvent, attempts: Int): Long = clock() + when (event.urgency) {
        SparkUrgency.IMMEDIATE -> 0
        SparkUrgency.SOON -> 10_000
        SparkUrgency.LATER -> 60_000
    } + attempts * requeueDelayMs
}
