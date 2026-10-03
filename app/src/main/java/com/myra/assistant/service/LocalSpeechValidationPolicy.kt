package com.myra.assistant.service

data class LocalSpeechValidationPolicy(
    val maxAttempts: Int,
    val timeoutMs: Long,
    val speakFallback: Boolean,
    val trustBufferedNaturalAudio: Boolean = false,
    val isolateFromMicDuringGeneration: Boolean = false,
    val bufferUntilValidated: Boolean = false,
    val resumeMicImmediatelyAfterPlayback: Boolean = false
) {
    companion object {
        val DEFAULT = LocalSpeechValidationPolicy(2, 8_000L, false)
        val MEMORY = LocalSpeechValidationPolicy(
            maxAttempts = 2,
            timeoutMs = 3_500L,
            speakFallback = false,
            trustBufferedNaturalAudio = true,
            isolateFromMicDuringGeneration = true,
            // The exact deterministic text is already visible. Releasing after a
            // matching spoken prefix prevents Gemini's late turnComplete ordering
            // from turning a valid natural reply into silence.
            bufferUntilValidated = false,
            resumeMicImmediatelyAfterPlayback = true
        )
    }
}
