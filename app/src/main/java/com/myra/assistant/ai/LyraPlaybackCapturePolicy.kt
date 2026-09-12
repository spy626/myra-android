package com.myra.assistant.ai

/** Explicit opt-in for recording LYRA's generated voice in user-approved captures. */
object LyraPlaybackCapturePolicy {
    const val useCapturableMediaUsage = true
    const val allowExternalPlaybackCapture = true

    fun shouldAcceptModelAudio(
        suppressed: Boolean,
        assistantAlreadySpeaking: Boolean,
        mediaGuardAllowsResponse: Boolean
    ): Boolean = !suppressed && (assistantAlreadySpeaking || mediaGuardAllowsResponse)
}
