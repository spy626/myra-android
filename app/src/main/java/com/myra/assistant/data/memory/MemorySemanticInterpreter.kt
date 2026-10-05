package com.myra.assistant.data.memory

import java.util.Locale
import org.json.JSONObject

enum class MemorySemanticIntent {
    ADD_FACT, ADD_RELATIONSHIP, REMOVE_RELATIONSHIP, REPLACE_RELATIONSHIP, ADD_LINKED_FACT,
    UPDATE_FACT, SUPERSEDE_FACT, INVALIDATE_FACT, RENAME_ENTITY, DELETE_ENTITY, RECALL,
    ADD_EPISODE, ADD_GOAL, UPDATE_GOAL, ADD_IDEA, ADD_PROJECT, ADD_SOLUTION, ADD_WORKFLOW,
    TRANSIENT_CONTEXT, CLARIFY, NONE
}

enum class PersonRelationship(val key: String) {
    FRIEND("friend"), GOOD_FRIEND("good_friend"), BEST_FRIEND("best_friend")
}

enum class MemoryTemporalScope { CURRENT, HISTORICAL, TEMPORARY, RECURRING, UNSPECIFIED }
enum class MemoryAssertionMode { USER_ASSERTED, HYPOTHETICAL, REPORTED_SPEECH, QUESTION }

/** Structured meaning proposed by the existing Gemini Live session; never Room authority. */
data class MemorySemanticFrame(
    val intent: MemorySemanticIntent,
    val person: String? = null,
    val replacementPerson: String? = null,
    val relationship: PersonRelationship? = null,
    /** Independently interpreted strength grounded by [sourceSpan]. */
    val semanticRelationship: PersonRelationship? = null,
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
    val resolvedEntityId: String? = null,
    val sourceEpisodeIds: List<String> = emptyList(),
    val assertionMode: MemoryAssertionMode = MemoryAssertionMode.USER_ASSERTED
)

/** One completed turn may carry a bounded compound set of independent propositions. */
data class FinalMemoryTurnPlan(
    val sourceText: String,
    val operations: List<MemorySemanticFrame> = emptyList(),
    val decision: MemoryDecision,
    val requiresClarification: Boolean = false,
    val rejectionReason: String? = null,
    val displayProjection: String? = null
)

/** Shared key normalization only. Natural-language interpretation does not live here. */
object MemorySemanticIdentity {
    fun token(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(48)
}

/**
 * Shared model->Android handoff contract for natural memory turns.
 *
 * The model proposes semantic meaning; Android still owns final-turn evidence,
 * safety, authorization, Room persistence and post-write verification.
 */
object MemoryProposalUsagePolicy {
    const val SYSTEM_REQUIREMENT =
        "When the current USER turn clearly expresses a stable, reusable user fact such as a preference, communication style, project, goal, habit, workflow, app-usage pattern, solution, or relationship, you MUST call propose_user_memory before speaking any acknowledgement, even when the user did not say remember. If ASR or transliteration spelling is noisy but the intended meaning is clear enough that you would verbally acknowledge the fact, still call propose_user_memory. Do not call it for questions, hypotheticals, reported speech, temporary context, guesses, or sensitive credentials. Android alone decides whether anything is persisted."

    const val TOOL_DESCRIPTION =
        "MUST be called before speaking whenever the current completed USER turn clearly asserts a stable, reusable personal fact that should survive future turns, including natural preferences and communication style even without remember/save wording. If ASR or transliteration spelling is noisy but the intended meaning is clear enough to acknowledge, still propose the semantic meaning. Never invent meaning: source_span must stay near-verbatim current-turn evidence while fact may normalize or translate that same meaning. Do not propose questions, hypotheticals, reported speech, temporary context, guesses, secrets, or unsupported inference. Return bounded independent AIRI semantic actions. Android validates final-turn structure, literals, attribution, lifecycle and safety and alone owns persistence."

    const val SOURCE_SPAN_DESCRIPTION =
        "Copy the shortest near-verbatim supporting words from the CURRENT USER transcript. Preserve ASR/transliteration spelling and noise; do not translate or normalize this field."

    const val FACT_DESCRIPTION =
        "Normalized semantic meaning for fact/linked-fact operations and episode summary. You may correct ASR spelling or translate only when preserving the clearly expressed current-turn meaning; never invent content."

    const val MEMORY_KEY_DESCRIPTION =
        "Stable semantic dimension for the normalized fact, such as response_length or preferred_language. Do not encode transient wording, timestamps, or unsupported details."
}

/** Bounded same-turn accumulation; repeated Live tool calls cannot overwrite or double-run meaning. */
object StagedMemoryProposalPolicy {
    fun merge(existing: List<MemorySemanticFrame>, incoming: List<MemorySemanticFrame>, limit: Int = 4): List<MemorySemanticFrame> =
        (existing + incoming).distinctBy {
            listOf(it.intent, it.person, it.replacementPerson, it.semanticRelationship,
                it.stableKey, it.fact).joinToString("|")
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
            val temporal = value.enumValue<MemoryTemporalScope>("temporal_scope") ?: MemoryTemporalScope.UNSPECIFIED
            val category = value.enumValue<MemoryCategory>("category")
            val assertionMode = value.enumValue<MemoryAssertionMode>("assertion_mode") ?: MemoryAssertionMode.USER_ASSERTED
            val critical = value.stringArray("critical_literals", 8)
            val participants = value.stringArray("participants", 6)
            val fact = value.optString("fact").trim().takeIf(String::isNotEmpty)
            MemorySemanticFrame(
                intent = intent,
                person = value.optString("person").trim().takeIf(String::isNotEmpty),
                replacementPerson = value.optString("replacement_person").trim().takeIf(String::isNotEmpty),
                // Legacy duplicate enum fields are deliberately ignored. The
                // source-grounded semantic field is the sole strength authority;
                // the coordinator derives the persistence operation from it.
                semanticRelationship = value.enumValue<PersonRelationship>("semantic_relationship"),
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
                goal = if (intent in setOf(MemorySemanticIntent.ADD_GOAL, MemorySemanticIntent.UPDATE_GOAL)) GoalMemoryPayload(
                    value.optString("goal_title"), fact, value.optString("goal_status", "ACTIVE"),
                    value.optInt("priority", 0).coerceIn(0, 5), value.optInt("progress", 0).coerceIn(0, 100)
                ) else null,
                sourceEpisodeIds = value.stringArray("source_episode_ids", 8),
                assertionMode = assertionMode
            )
        }
    }

    private inline fun <reified T : Enum<T>> JSONObject.enumValue(key: String): T? =
        optString(key).trim().takeIf(String::isNotEmpty)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
    private fun JSONObject.stringArray(key: String, limit: Int): List<String> = optJSONArray(key)?.let { array ->
        (0 until minOf(array.length(), limit)).mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) }
    }.orEmpty()
}
