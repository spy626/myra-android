package com.myra.assistant.core

data class AssistantResult(
    val success: Boolean,
    val verified: Boolean,
    val actionType: String,
    val target: String? = null,
    val spokenMessage: String,
    val technicalError: String? = null,
    val requiresConfirmation: Boolean = false,
    val shouldResumeListening: Boolean = true
)
