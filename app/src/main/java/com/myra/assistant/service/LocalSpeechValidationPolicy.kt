package com.myra.assistant.service

data class LocalSpeechValidationPolicy(
    val maxAttempts: Int,
    val timeoutMs: Long,
    val speakFallback: Boolean,
    val trustBufferedNaturalAudio: Boolean = false
) {
    companion object {
        val DEFAULT = LocalSpeechValidationPolicy(2, 8_000L, false)
        val MEMORY = LocalSpeechValidationPolicy(
            maxAttempts = 2,
            timeoutMs = 3_500L,
            speakFallback = false,
            trustBufferedNaturalAudio = true
        )
    }
}
