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
        """(?iu)\b(?:undo|revert|rollback|wapas|vaapas|wapis)\b"""
    )
    private val correctionCue = Regex(
        """(?iu)\b(?:wrong|galat|incorrect|nahi|nahin|nehi|not\s+right|not\s+what\s+i\s+(?:asked|meant)|maine\s+ye\s+nahi|aisa\s+nahi)\b"""
    )
    private val confirmationCue = Regex(
        """(?iu)\b(?:right|correct|sahi|perfect|good|worked|works|theek|thik|yes|haan|han)\b"""
    )
    private val workflowReferenceCue = Regex(
        """(?iu)\b(?:this|that|it|same|last|previous|prior|ye|yah|vo|woh|uska|iska|change|work|task|commit|ci|build|result)\b"""
    )

    private fun normalized(raw: String): String = raw.trim()
        .replace(Regex("""[\s\p{Z}]+"""), " ")
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
