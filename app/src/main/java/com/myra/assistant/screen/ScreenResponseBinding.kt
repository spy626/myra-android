package com.myra.assistant.screen

/** Immutable logical query identity with a one-time Live generation latch. */
class ScreenResponseBinding(
    val userTurnId: Long,
    val screenQueryId: String,
    val screenSessionId: String,
    private val generationFloor: Long
) {
    var screenGenerationId: Long = 0L
        private set

    @Synchronized fun acceptsGeneration(generationId: Long): Boolean {
        if (generationId <= generationFloor) return false
        if (screenGenerationId == 0L) screenGenerationId = generationId
        return screenGenerationId == generationId
    }

    fun matches(queryId: String, sessionId: String, turnId: Long): Boolean =
        screenQueryId == queryId && screenSessionId == sessionId && userTurnId == turnId
}
