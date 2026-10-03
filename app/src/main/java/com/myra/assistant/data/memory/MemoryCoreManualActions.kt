package com.myra.assistant.data.memory

/** Manual UI writes use the same final owner; no repository-side interpretation exists. */
object MemoryCoreManualActions {
    suspend fun add(coordinator: MemoryBrainCoordinator, fact: String, category: MemoryCategory): MemoryWriteResult {
        return coordinator.addFromManualUi(fact, category)
    }
}
