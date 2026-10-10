package com.myra.assistant.data.memory

enum class MemoryCategory {
    IDENTITY, PREFERENCE, PERSON, PROJECT, GOAL, HABIT, LIFE_EVENT,
    COMMUNICATION_STYLE, WORKFLOW, APP_USAGE, CONTENT_INTEREST, CURRENT_INTEREST, IDEA, SOLUTION
}
enum class MemorySensitivity { LOW, PERSONAL, SENSITIVE, PROHIBITED }
enum class MemoryProvenance {
    USER_EXPLICIT_MEMORY_COMMAND, USER_DIRECT_STATEMENT, VERIFIED_MEMORY_CORRECTION,
    BEHAVIOR_PATTERN, SCREEN_OBSERVATION, GEMINI_GROUNDED_PROPOSAL,
    MANUAL_UI_EDIT, MANUAL_UI_SEED
}
enum class MemoryLifecycleStatus { ACTIVE, OBSERVED, WEAKENING, SUPERSEDED, HISTORICAL, INACTIVE }

/**
 * Native Android organization buckets inspired by OpenViking's typed memory layout.
 * These are projections over the existing AIRI/Plast-Mem Room truth, not a second store.
 */
enum class MemoryOrganizationBucket {
    PROFILE, PREFERENCES, ENTITIES, EVENTS, EXPERIENCES, GOALS, PROJECTS, OTHER
}

object MemoryOrganization {
    fun bucket(category: String?, kind: String? = null): MemoryOrganizationBucket = when {
        kind == "EPISODE" || category == MemoryCategory.LIFE_EVENT.name -> MemoryOrganizationBucket.EVENTS
        category in setOf(
            MemoryCategory.PREFERENCE.name,
            MemoryCategory.COMMUNICATION_STYLE.name,
            MemoryCategory.HABIT.name,
            MemoryCategory.APP_USAGE.name,
            MemoryCategory.CONTENT_INTEREST.name,
            MemoryCategory.CURRENT_INTEREST.name
        ) -> MemoryOrganizationBucket.PREFERENCES
        category == MemoryCategory.IDENTITY.name -> MemoryOrganizationBucket.PROFILE
        category == MemoryCategory.PERSON.name -> MemoryOrganizationBucket.ENTITIES
        category == MemoryCategory.GOAL.name -> MemoryOrganizationBucket.GOALS
        category in setOf(MemoryCategory.PROJECT.name, MemoryCategory.IDEA.name) -> MemoryOrganizationBucket.PROJECTS
        category in setOf(MemoryCategory.WORKFLOW.name, MemoryCategory.SOLUTION.name) -> MemoryOrganizationBucket.EXPERIENCES
        else -> MemoryOrganizationBucket.OTHER
    }
}

data class MemoryDiffItem(
    val action: String,
    val bucket: MemoryOrganizationBucket,
    val category: String?,
    val semanticKey: String?,
    val memoryId: String?,
    val statement: String?,
    val verified: Boolean,
    val episodeId: String? = null,
    val calibratedAt: Long? = null
)

data class MemoryDiffSnapshot(
    val conversationId: String,
    val turnId: Long,
    val items: List<MemoryDiffItem>,
    val generatedAt: Long = System.currentTimeMillis()
) {
    val verified: Boolean get() = items.isNotEmpty() && items.all(MemoryDiffItem::verified)
    val adds: Int get() = items.count { it.action == "NEW" }
    val reinforces: Int get() = items.count { it.action == "REINFORCE" }
    val updates: Int get() = items.count { it.action == "UPDATE" }
    val invalidates: Int get() = items.count { it.action == "INVALIDATE" }
}

sealed class MemoryWriteResult {
    data class Saved(val id: String) : MemoryWriteResult()
    data object NeedsPermission : MemoryWriteResult()
    data class Rejected(val reason: String) : MemoryWriteResult()
}
