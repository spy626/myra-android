package com.myra.assistant.ui.workspace

/**
 * Explicit retry UI policy. A provider rejection is not automatically replayed.
 * Z.ai HTTP 429 is special: immediate one-tap resend is suppressed so the user
 * cannot accidentally hammer the same rate-limit window.
 */
internal object WorkspaceChatRetryPolicy {
    data class Decision(val allowImmediateRetry: Boolean, val rateLimited: Boolean) {
        fun note(hasAttachments: Boolean): String = when {
            rateLimited -> "Immediate Retry is disabled after Z.ai HTTP 429. " +
                "Wait as shown above, then send again manually. Nothing is resent automatically; no paid fallback."
            else -> "Retry the same complete message? " +
                (if (hasAttachments) "The same selected attachments will be read again. " else "") +
                "No paid fallback."
        }
    }

    fun decision(provider: WorkspaceChatGateway.Provider, failure: Throwable): Decision {
        val rateLimited = provider == WorkspaceChatGateway.Provider.ZAI_FREE &&
            failure is WorkspaceZaiFree.RateLimitException
        return Decision(allowImmediateRetry = !rateLimited, rateLimited = rateLimited)
    }
}
