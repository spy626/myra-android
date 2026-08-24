package com.myra.assistant.service

data class LocalSpeechValidationPolicy(
    val maxAttempts: Int,
    val timeoutMs: Long,
    val speakFallback: Boolean
) {
    companion object {
        val DEFAULT = LocalSpeechValidationPolicy(2, 8_000L, false)
        val MEMORY = LocalSpeechValidationPolicy(1, 2_500L, true)
    }
}
