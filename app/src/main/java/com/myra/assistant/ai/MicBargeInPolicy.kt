package com.myra.assistant.ai

internal object MicBargeInPolicy {
    fun shouldForward(
        muted: Boolean,
        speakerActive: Boolean,
        bargeInEnabled: Boolean,
        confirmedPlaybackBargeIn: Boolean = false
    ): Boolean = !muted && (!speakerActive || bargeInEnabled && confirmedPlaybackBargeIn)
}
