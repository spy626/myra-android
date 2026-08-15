package com.myra.assistant.core

object AssistantStatePolicy {
    fun canTransition(from: AssistantState, to: AssistantState): Boolean {
        if (from == to) return true
        if (to == AssistantState.ERROR || to == AssistantState.STOPPED) return true
        return when (from) {
            AssistantState.IDLE -> to in setOf(AssistantState.WAKE_WORD_LISTENING, AssistantState.COMMAND_LISTENING, AssistantState.PROCESSING)
            AssistantState.WAKE_WORD_LISTENING -> to in setOf(AssistantState.COMMAND_LISTENING, AssistantState.PROCESSING, AssistantState.IDLE)
            AssistantState.COMMAND_LISTENING -> to in setOf(AssistantState.PROCESSING, AssistantState.RESUMING_WAKE_WORD, AssistantState.IDLE)
            AssistantState.PROCESSING -> to in setOf(AssistantState.EXECUTING_ACTION, AssistantState.SPEAKING, AssistantState.RESUMING_WAKE_WORD, AssistantState.IDLE)
            AssistantState.EXECUTING_ACTION -> to in setOf(AssistantState.SPEAKING, AssistantState.RESUMING_WAKE_WORD, AssistantState.IDLE)
            AssistantState.SPEAKING -> to in setOf(AssistantState.RESUMING_WAKE_WORD, AssistantState.IDLE)
            AssistantState.RESUMING_WAKE_WORD -> to in setOf(AssistantState.WAKE_WORD_LISTENING, AssistantState.COMMAND_LISTENING, AssistantState.IDLE)
            AssistantState.ERROR -> to in setOf(AssistantState.RESUMING_WAKE_WORD, AssistantState.WAKE_WORD_LISTENING, AssistantState.IDLE)
            AssistantState.STOPPED -> to in setOf(AssistantState.IDLE, AssistantState.WAKE_WORD_LISTENING)
        }
    }
}
