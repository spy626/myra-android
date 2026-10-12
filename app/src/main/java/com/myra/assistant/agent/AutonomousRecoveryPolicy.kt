package com.myra.assistant.agent

/** Fully automatic restart re-entry for previously authorized public READ-ONLY search.
 * No second agent, private data, app-control replay or invented user permission.
 */
object AutonomousRecoveryPolicy {
    data class PublicSearch(val query: String)
    private val readOnly = setOf(
        ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH,
        ToolCapability.OBSERVE_SCREEN, ToolCapability.VERIFY_SCREEN,
    )
    private val sensitive = Regex(
        """(?iu)\b(?:password|passcode|passphrase|pin|otp|cvv|private|secret|""" +
            """token|api[ -]?key|bank|card|account|aadhaar|aadhar|medical|health|""" +
            """diagnosis|email|phone|whatsapp|message|chat|delete|send|purchase|pay|""" +
            """upload|edit|write|install|login|sign[ -]?in|contact|address|""" +
            """personal|mera|meri|mine|my)\b"""
    )

    fun sanitizePublicQuery(raw: String?): String? {
        val query = raw?.trim()?.replace(Regex("""\s+"""), " ") ?: return null
        if (query.length !in 3..140 || query.any(Char::isISOControl) ||
            sensitive.containsMatchIn(query) || query.contains('@') ||
            query.contains("://") || query.contains(92.toChar()) ||
            query.contains(34.toChar()) || query.contains(39.toChar()) ||
            query.contains('&') || query.contains(';')) return null
        return query
    }

    fun safeSearch(task: GeneralRuntimeTask): PublicSearch? {
        val intent = task.intent
        if (task.turnId <= 0L || task.status in setOf(
                AgentRuntimeStatus.COMPLETED, AgentRuntimeStatus.FAILED,
                AgentRuntimeStatus.CANCELLED, AgentRuntimeStatus.WAITING_FOR_USER_AUTH
            ) || !intent.requiresAction ||
            intent.turnIntent !in setOf(TurnIntent.ACTION_REQUEST, TurnIntent.MULTI_STEP_GOAL) ||
            intent.requiredCapabilities.isEmpty() ||
            intent.requiredCapabilities.none {
                it == ToolCapability.BROWSER_SEARCH || it == ToolCapability.WEB_SEARCH
            } || !readOnly.containsAll(intent.requiredCapabilities)) return null
        val parsed = FinalSearchHandoff.parse(intent.originalUtterance) ?: return null
        if (parsed.explicitDestination != null) return null
        // Compare canonical topics to tolerate legacy "search X karo" parsing.
        // The complete final user turn and bound runtime query must still agree.
        val imperative = Regex("""(?iu)\s+(?:karo|kar do)$""")
        val original = sanitizePublicQuery(parsed.query)
            ?.replace(imperative, "") ?: return null
        val bound = sanitizePublicQuery(intent.parameters["query"])
            ?.replace(imperative, "") ?: return null
        if (!original.equals(bound, ignoreCase = true)) return null
        return PublicSearch(sanitizePublicQuery(original) ?: return null)
    }

    fun autoResumeAllowed(
        checkpoint: GeneralTaskCheckpointStore.Checkpoint,
        nowMs: Long,
    ): Boolean = checkpoint.requiresReview &&
        checkpoint.safeSearchQuery != null &&
        checkpoint.status != AgentRuntimeStatus.WAITING_FOR_USER_AUTH &&
        checkpoint.savedAtMs <= nowMs &&
        nowMs - checkpoint.savedAtMs <= 24L * 60 * 60 * 1000
}
