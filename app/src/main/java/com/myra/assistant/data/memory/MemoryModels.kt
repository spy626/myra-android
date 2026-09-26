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

sealed class MemoryWriteResult {
    data class Saved(val id: String) : MemoryWriteResult()
    data object NeedsPermission : MemoryWriteResult()
    data class Rejected(val reason: String) : MemoryWriteResult()
}
