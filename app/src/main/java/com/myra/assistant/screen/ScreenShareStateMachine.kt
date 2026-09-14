package com.myra.assistant.screen

class ScreenShareStateMachine(initial: ScreenShareState = ScreenShareState.IDLE) {
    var state: ScreenShareState = initial
        private set

    fun transition(next: ScreenShareState): Boolean {
        val allowed = next in transitions.getValue(state)
        if (allowed) state = next
        return allowed
    }

    companion object {
        private val terminalRestart = setOf(ScreenShareState.REQUESTING_PERMISSION)
        private val transitions = mapOf(
            ScreenShareState.IDLE to setOf(ScreenShareState.REQUESTING_PERMISSION),
            ScreenShareState.REQUESTING_PERMISSION to setOf(ScreenShareState.ACTIVE, ScreenShareState.STOPPING, ScreenShareState.ERROR, ScreenShareState.STOPPED),
            ScreenShareState.ACTIVE to setOf(ScreenShareState.PAUSED, ScreenShareState.STOPPING, ScreenShareState.ERROR),
            ScreenShareState.PAUSED to setOf(ScreenShareState.RESUMING, ScreenShareState.STOPPING, ScreenShareState.ERROR),
            ScreenShareState.RESUMING to setOf(ScreenShareState.ACTIVE, ScreenShareState.STOPPING, ScreenShareState.ERROR),
            ScreenShareState.STOPPING to setOf(ScreenShareState.STOPPED, ScreenShareState.ERROR),
            ScreenShareState.STOPPED to terminalRestart,
            ScreenShareState.ERROR to terminalRestart + ScreenShareState.STOPPING
        )
    }
}
