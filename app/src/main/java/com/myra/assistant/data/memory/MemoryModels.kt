package com.myra.assistant.data.memory

enum class MemoryCategory {
    IDENTITY, PREFERENCE, PERSON, PROJECT, GOAL, HABIT, LIFE_EVENT,
    COMMUNICATION_STYLE, WORKFLOW, APP_USAGE, CONTENT_INTEREST, CURRENT_INTEREST, SOLUTION
}
enum class MemorySensitivity { LOW, PERSONAL, SENSITIVE, PROHIBITED }
enum class MemorySaveDecision { AUTO_SAVE, ASK_PERMISSION, REJECT }
enum class MemoryProvenance {
    USER_EXPLICIT_MEMORY_COMMAND, USER_DIRECT_STATEMENT, VERIFIED_MEMORY_CORRECTION,
    BEHAVIOR_PATTERN, SCREEN_OBSERVATION, GEMINI_GROUNDED_PROPOSAL,
    MANUAL_UI_EDIT, MANUAL_UI_SEED, LEGACY
}
enum class MemoryLifecycleStatus { ACTIVE, SUPERSEDED, HISTORICAL, INACTIVE }

data class MemoryCandidate(
    val category: MemoryCategory,
    val fact: String,
    val stableKey: String,
    val sensitivity: MemorySensitivity,
    val confidence: Double,
    val explicitlyRequested: Boolean = false,
    val source: String = "conversation",
    val provenance: MemoryProvenance = MemoryProvenance.USER_DIRECT_STATEMENT,
    val entityId: String? = null,
    val entityName: String? = null,
    val observationMetadata: String? = null
)

sealed class MemoryWriteResult {
    data class Saved(val id: String) : MemoryWriteResult()
    data object NeedsPermission : MemoryWriteResult()
    data class Rejected(val reason: String) : MemoryWriteResult()
}
