package com.myra.assistant.ai

internal enum class SpeechActivityEvent { NONE, STARTED, ENDED }

/** Energy/VAD state over AudioEngine's 100 ms, echo-cancelled microphone frames. */
internal class SpeechActivityDetector(
    private val startThreshold: Float = 0.018f,
    private val continueThreshold: Float = 0.010f,
    private val startFrames: Int = 1,
    // AudioEngine supplies 50 ms frames; 14 quiet frames represent a semantic
    // 700 ms end-of-speech window rather than a post-response quarantine.
    private val endFrames: Int = 14
) {
    var active: Boolean = false; private set
    private var voicedFrames = 0
    private var quietFrames = 0

    @Synchronized fun update(rms: Float): SpeechActivityEvent {
        if (!active) {
            voicedFrames = if (rms >= startThreshold) voicedFrames + 1 else 0
            if (voicedFrames >= startFrames) {
                active = true
                voicedFrames = 0
                quietFrames = 0
                return SpeechActivityEvent.STARTED
            }
            return SpeechActivityEvent.NONE
        }
        quietFrames = if (rms < continueThreshold) quietFrames + 1 else 0
        if (quietFrames >= endFrames) {
            active = false
            quietFrames = 0
            return SpeechActivityEvent.ENDED
        }
        return SpeechActivityEvent.NONE
    }

    @Synchronized fun reset() {
        active = false
        voicedFrames = 0
        quietFrames = 0
    }
}
