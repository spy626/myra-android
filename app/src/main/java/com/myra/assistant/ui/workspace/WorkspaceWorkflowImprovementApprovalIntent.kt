package com.myra.assistant.ui.workspace

/**
 * Conservative explicit-approval detector.
 *
 * Grounding to one exact visible proposal is handled separately; this object only interprets the
 * current USER turn and never activates anything.
 */
internal object WorkspaceWorkflowImprovementApprovalIntent {
    data class Decision(val confidence: Double)

    private val approvalCue = Regex("""(?iu)\b(?:approve|manzoor)\b""")
    private val denialCue = Regex(
        """(?iu)\b(?:do\s+not|don't|dont|mat|nahi|nahin|nehi|cancel|reject)\b"""
    )
    private val questionCue = Regex(
        """(?iu)(?:\?|\b(?:can|could|should|may|kya|kaise|how|whether)\b)"""
    )
    private val explicitCurrentApproval = Regex(
        """(?iu)\b(?:i\s+approve|approve\s+(?:this|it|proposal|karo|kar\s+do)|haan\s+approve|han\s+approve|yes\s+approve|manzoor\s+hai)\b"""
    )

    fun decide(raw: String): Decision? {
        val text = raw.trim().replace(Regex("""[\s\p{Z}]+"""), " ").take(1_000)
        if (text.isBlank() || WorkspaceSourceContext.containsPossibleSecret(text)) return null
        if (!approvalCue.containsMatchIn(text) || denialCue.containsMatchIn(text)) return null
        if (questionCue.containsMatchIn(text) && !explicitCurrentApproval.containsMatchIn(text)) {
            return null
        }
        if (!explicitCurrentApproval.containsMatchIn(text) && text.length > 120) return null
        return Decision(
            confidence = if (explicitCurrentApproval.containsMatchIn(text)) 0.99 else 0.95,
        )
    }
}
