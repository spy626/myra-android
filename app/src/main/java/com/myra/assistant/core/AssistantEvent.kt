package com.myra.assistant.core

sealed interface AssistantEvent {
    data object StartListening : AssistantEvent
    data class Heard(val text: String) : AssistantEvent
    data object SpeechStarted : AssistantEvent
    data object SpeechFinished : AssistantEvent
    data class Failed(val reason: String) : AssistantEvent
    data object Stop : AssistantEvent
}
