package com.myra.assistant.data.memory

/** Manual UI writes use the same final owner; no repository-side interpretation exists. */
object MemoryCoreManualActions {
    suspend fun add(coordinator: MemoryBrainCoordinator, fact: String, category: MemoryCategory): MemoryWriteResult {
        val clean = fact.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 3..500) return MemoryWriteResult.Rejected("Memory must contain 3 to 500 characters.")
        val turn = System.currentTimeMillis()
        AiriMemoryRuntime.claimTurn("manual-ui", turn)
        val evidence = AuthoritativeMemoryTurnEvidence(turn, clean, clean, sessionId = "manual-ui")
        val frame = MemorySemanticFrame(MemorySemanticIntent.ADD_FACT, temporalScope = MemoryTemporalScope.CURRENT,
            fact = clean, category = category, stableKey = "manual:${category.name}:${AiriText.semanticKey(clean).take(48)}",
            confidence = 1.0, sourceSpan = clean, sourceTurnId = turn)
        val plan = coordinator.prepareFinalTurn(evidence, listOf(frame))
        return ((coordinator.executeFinalTurnPlan(plan, evidence) as? MemoryBrainOutcome.Mutated)?.result)
            ?: MemoryWriteResult.Rejected("Memory write was not verified.")
    }
}
