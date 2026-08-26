package com.myra.assistant.ai

internal object MicBargeInPolicy {
    fun shouldForward(muted: Boolean, speakerActive: Boolean, bargeInEnabled: Boolean): Boolean =
        !muted && (!speakerActive || bargeInEnabled)
}
