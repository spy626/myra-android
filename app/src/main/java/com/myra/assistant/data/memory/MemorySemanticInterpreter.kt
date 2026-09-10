package com.myra.assistant.data.memory

import java.util.Locale
import org.json.JSONObject

enum class MemorySemanticIntent {
    ADD_FACT, ADD_RELATIONSHIP, REMOVE_RELATIONSHIP, REPLACE_RELATIONSHIP, ADD_LINKED_FACT,
    UPDATE_FACT, SUPERSEDE_FACT, RENAME_ENTITY, DELETE_ENTITY, RECALL,
    ADD_EPISODE, ADD_GOAL, TRANSIENT_CONTEXT, CLARIFY, NONE
}

enum class PersonRelationship(val key: String) {
    FRIEND("friend"), GOOD_FRIEND("good_friend"), BEST_FRIEND("best_friend")
}

enum class MemoryTemporalScope { CURRENT, HISTORICAL, TEMPORARY, RECURRING, UNSPECIFIED }

/** Structured meaning proposed by the existing Gemini Live session; never Room authority. */
data class MemorySemanticFrame(
    val intent: MemorySemanticIntent,
    val person: String? = null,
    val replacementPerson: String? = null,
    val relationship: PersonRelationship? = null,
    val replacementRelationship: PersonRelationship? = null,
    val temporalScope: MemoryTemporalScope = MemoryTemporalScope.UNSPECIFIED,
    val fact: String? = null,
    val category: MemoryCategory? = null,
    val stableKey: String? = null,
    val confidence: Double = 0.0,
    val evidence: String = "",
    val sourceSpan: String = evidence,
    val sourceTurnId: Long = 0L,
    val sourceSessionId: String = "compatibility",
    val criticalLiterals: List<String> = emptyList(),
    val episode: EpisodicMemoryPayload? = null,
    val goal: GoalMemoryPayload? = null,
    val resolvedEntityId: String? = null
)

/** One completed turn may carry a bounded compound set of independent propositions. */
data class FinalMemoryTurnPlan(
    val sourceText: String,
    val operations: List<MemorySemanticFrame> = emptyList(),
    val decision: MemoryDecision,
    val requiresClarification: Boolean = false,
    val rejectionReason: String? = null
)

/** Shared key normalization only. Natural-language interpretation does not live here. */
object MemorySemanticIdentity {
    fun token(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(48)
}

/** Bounded same-turn accumulation; repeated Live tool calls cannot overwrite or double-run meaning. */
object StagedMemoryProposalPolicy {
    fun merge(existing: List<MemorySemanticFrame>, incoming: List<MemorySemanticFrame>, limit: Int = 4): List<MemorySemanticFrame> =
        (existing + incoming).distinctBy {
            listOf(it.intent, it.person, it.replacementPerson, it.relationship,
                it.replacementRelationship, it.stableKey, it.fact).joinToString("|")
        }.take(limit)
}

/** Platform boundary parser for the existing Gemini tool; it performs no interpretation or I/O. */
object GeminiMemoryOperationParser {
    fun parse(args: JSONObject): List<MemorySemanticFrame> {
        val values = args.optJSONArray("operations") ?: return emptyList()
        return (0 until minOf(values.length(), 4)).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            val intent = runCatching { MemorySemanticIntent.valueOf(value.optString("intent")) }.getOrNull()
                ?: return@mapNotNull null
            val relationship = value.enumValue<PersonRelationship>("relationship")
            val replacementRelationship = value.enumValue<PersonRelationship>("replacement_relationship")
            val temporal = value.enumValue<MemoryTemporalScope>("temporal_scope") ?: MemoryTemporalScope.UNSPECIFIED
            val category = value.enumValue<MemoryCategory>("category")
            val critical = value.stringArray("critical_literals", 8)
            val participants = value.stringArray("participants", 6)
            val fact = value.optString("fact").trim().takeIf(String::isNotEmpty)
            MemorySemanticFrame(
                intent = intent,
                person = value.optString("person").trim().takeIf(String::isNotEmpty),
                replacementPerson = value.optString("replacement_person").trim().takeIf(String::isNotEmpty),
                relationship = relationship,
                replacementRelationship = replacementRelationship,
                temporalScope = temporal,
                fact = fact,
                category = category,
                stableKey = value.optString("memory_key").trim().takeIf(String::isNotEmpty),
                confidence = value.optDouble("confidence", 0.0),
                evidence = value.optString("evidence"),
                sourceSpan = value.optString("source_span", value.optString("evidence")),
                criticalLiterals = critical,
                episode = if (intent == MemorySemanticIntent.ADD_EPISODE) EpisodicMemoryPayload(
                    value.optString("event_type"), fact.orEmpty(), participants,
                    value.optDouble("importance", .5).coerceIn(0.0, 1.0)
                ) else null,
                goal = if (intent == MemorySemanticIntent.ADD_GOAL) GoalMemoryPayload(
                    value.optString("goal_title"), fact, value.optString("goal_status", "ACTIVE"),
                    value.optInt("priority", 0).coerceIn(0, 5), value.optInt("progress", 0).coerceIn(0, 100)
                ) else null
            )
        }
    }

    private inline fun <reified T : Enum<T>> JSONObject.enumValue(key: String): T? =
        optString(key).trim().takeIf(String::isNotEmpty)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
    private fun JSONObject.stringArray(key: String, limit: Int): List<String> = optJSONArray(key)?.let { array ->
        (0 until minOf(array.length(), limit)).mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) }
    }.orEmpty()
}
