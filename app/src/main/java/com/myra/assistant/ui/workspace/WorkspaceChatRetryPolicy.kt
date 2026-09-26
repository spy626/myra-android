package com.myra.assistant.ui.workspace

/** Explicit user retry only. Ordinary Chat never retries a failed provider automatically. */
internal object WorkspaceChatRetryPolicy {
    data class Decision(val allowImmediateRetry: Boolean = true) {
        fun note(hasAttachments: Boolean): String =
            "Retry the same complete message? " +
                (if (hasAttachments) "The same selected attachments will be read again. " else "") +
                "No paid fallback."
    }

    fun decision(): Decision = Decision()
}
