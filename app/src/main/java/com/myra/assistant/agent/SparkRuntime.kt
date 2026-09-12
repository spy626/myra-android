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

data class SparkGuidanceOption(
    val label: String, val steps: List<String>, val rationale: String? = null,
    val possibleOutcomes: List<String> = emptyList(), val risk: SparkRisk = SparkRisk.NONE,
    val fallback: List<String> = emptyList(), val triggers: List<String> = emptyList()
)
data class SparkGuidance(
    val type: SparkGuidanceType, val persona: Map<String, String> = emptyMap(),
    val options: List<SparkGuidanceOption>
)
data class SparkContextPatch(
    val source: String, val lane: String, val strategy: ContextMutation,
    val text: String, val generation: Long, val metadata: Map<String, String> = emptyMap()
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
    val payload: Map<String, String> = emptyMap(), val createdAt: Long = System.currentTimeMillis()
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
            val key = "${patch.source}:${patch.lane}"
            val current = latestGeneration[key] ?: Long.MIN_VALUE
            if (patch.generation < current) {
                trace(command.eventId, command.parentEventId, "spark-command", patch.lane, "STALE", "IGNORED")
                return SparkDispatchResult(false, stale = true, reason = "STALE_CONTEXT")
            }
            latestGeneration[key] = patch.generation
            contexts.ingest(ContextEntry(key, patch.text, 0, patch.generation), patch.strategy)
        }
        commandSink(command)
        trace(command.eventId, command.parentEventId, "spark-command", command.destinations.joinToString(","), command.intent.name, "DISPATCHED")
        return SparkDispatchResult(true)
    }

    fun notify(event: SparkNotifyEvent, policy: (SparkNotifyEvent) -> SparkNotifyDecision): SparkNotifyDecision {
        val decision = policy(event)
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
}
