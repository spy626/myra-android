package com.myra.assistant.data.memory

import java.util.Locale

enum class MemorySemanticIntent {
    ADD_RELATIONSHIP, REMOVE_RELATIONSHIP, REPLACE_RELATIONSHIP, ADD_LINKED_FACT,
    UPDATE_FACT, SUPERSEDE_FACT, RENAME_ENTITY, DELETE_ENTITY, RECALL,
    TRANSIENT_CONTEXT, CLARIFY, NONE
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
    val evidence: String = ""
)

/** One completed turn may carry a bounded compound set of independent propositions. */
data class FinalMemoryTurnPlan(
    val sourceText: String,
    val operations: List<MemorySemanticFrame> = emptyList(),
    val explicitCommand: MemoryCommand? = null,
    val decision: MemoryDecision,
    val requiresClarification: Boolean = false,
    val rejectionReason: String? = null
)

/** Shared key normalization only. Natural-language interpretation does not live here. */
object MemorySemanticIdentity {
    fun token(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(48)
}
