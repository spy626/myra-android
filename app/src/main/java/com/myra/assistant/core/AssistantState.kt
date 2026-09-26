package com.myra.assistant.core

enum class AssistantState {
    IDLE,
    WAKE_WORD_LISTENING,
    COMMAND_LISTENING,
    PROCESSING,
    EXECUTING_ACTION,
    SPEAKING,
    RESUMING_WAKE_WORD,
    ERROR,
    STOPPED
}
