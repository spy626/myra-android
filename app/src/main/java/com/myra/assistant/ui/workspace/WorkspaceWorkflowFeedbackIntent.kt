package com.myra.assistant.ui.workspace

/**
 * Conservative user-feedback detector for a recent verified workflow.
 *
 * Detection alone never binds to history. A caller must separately prove that the exact recent
 * verified workflow receipt is visible in the selected chat before persisting feedback.
 */
internal object WorkspaceWorkflowFeedbackIntent {
    enum class Kind { CONFIRM, CORRECT, UNDO }

    data class Decision(
        val kind: Kind,
        val confidence: Double,
    )

    private val undoCue = Regex(
        """(?iu)(?:undo|revert|rollback|wapas|vaapas|wapis)"""
    )
    private val correctionCue = Regex(
        """(?iu)(?:wrong|galat|incorrect|nahi|nahin|nehi|nots+right|nots+whats+is+(?:asked|meant)|maines+yes+nahi|aisas+nahi)"""
    )
    private val confirmationCue = Regex(
        """(?iu)(?:right|correct|sahi|perfect|good|worked|works|theek|thik|yes|haan|han)"""
    )
    private val workflowReferenceCue = Regex(
        """(?iu)(?:this|that|it|same|last|previous|prior|ye|yah|vo|woh|uska|iska|change|work|task|commit|ci|build|result)"""
    )

    private fun normalized(raw: String): String = raw.trim()
        .replace('’', ''')
        .replace(Regex("""[sp{Z}]+"""), " ")
        .take(1_000)

    fun looksLikeFeedback(raw: String): Boolean {
        val text = normalized(raw)
        if (text.isBlank() || WorkspaceSourceContext.containsPossibleSecret(text)) return false
        return undoCue.containsMatchIn(text) ||
            correctionCue.containsMatchIn(text) ||
            confirmationCue.containsMatchIn(text)
    }

    fun decide(
        raw: String,
        exactTargetVisibleInSelectedChat: Boolean,
    ): Decision? {
        if (!exactTargetVisibleInSelectedChat) return null
        val text = normalized(raw)
        if (text.isBlank() || WorkspaceSourceContext.containsPossibleSecret(text)) return null

        val kind = when {
            undoCue.containsMatchIn(text) -> Kind.UNDO
            correctionCue.containsMatchIn(text) -> Kind.CORRECT
            confirmationCue.containsMatchIn(text) -> Kind.CONFIRM
            else -> return null
        }

        val shortReply = text.length <= 80
        if (!shortReply && !workflowReferenceCue.containsMatchIn(text)) return null
        return Decision(
            kind = kind,
            confidence = if (workflowReferenceCue.containsMatchIn(text)) 0.96 else 0.90,
        )
    }
}
