package com.myra.assistant.data.memory

enum class MemoryCategory {
    IDENTITY, PREFERENCE, PERSON, PROJECT, GOAL, HABIT, LIFE_EVENT,
    COMMUNICATION_STYLE, WORKFLOW, APP_USAGE, SOLUTION
}
enum class MemorySensitivity { LOW, PERSONAL, SENSITIVE, PROHIBITED }
enum class MemorySaveDecision { AUTO_SAVE, ASK_PERMISSION, REJECT }

data class MemoryCandidate(
    val category: MemoryCategory,
    val fact: String,
    val stableKey: String,
    val sensitivity: MemorySensitivity,
    val confidence: Double,
    val explicitlyRequested: Boolean = false,
    val source: String = "conversation"
)

sealed class MemoryWriteResult {
    data class Saved(val id: String) : MemoryWriteResult()
    data object NeedsPermission : MemoryWriteResult()
    data class Rejected(val reason: String) : MemoryWriteResult()
}
