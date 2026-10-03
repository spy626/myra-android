package com.myra.assistant.ui.workspace

/**
 * Completed-answer safety boundary for an explicit advice-only turn.
 *
 * Current-turn execution authority is already enforced separately for tools,
 * project mutations and code actions. Never reject a completed conversational
 * answer using heuristic words like "screen", "create" or "connect": they can
 * describe a paper plan, an example, or a negative warning, and the rejected
 * model text is not even shown to the user. Planning constraints are supplied
 * once by WorkspacePracticalPlanningGuide.
 */
internal object WorkspacePlanningAnswerBoundary {
    // The model actually emitted executable/source content despite "no code".
    // Code fences with language tags are unmistakable; prose is not.
    private val sourceFence = Regex(
        """(?im)^\s*```(?:kotlin|kt|java|xml|html|css|javascript|js|typescript|ts|python|py|bash|sh|sql|gradle)\b"""
    )

    fun violation(latest: String, completedReply: String): String? {
        val shape = WorkspacePlanningBrief.parse(latest)
        if (!shape.adviceOnly || WorkspacePracticalPlanningGuide.instructions(latest).isBlank())
            return null
        if (sourceFence.containsMatchIn(completedReply))
            return "LYRA gave a source-code block although you asked for planning only. Reply not saved; no automatic or paid retry."
        // Show the answer. A free model's prose should not fail the entire chat
        // because an unreliable local regex guessed an implementation stage.
        return null
    }

    fun requireAcceptable(latest: String, completedReply: String): String {
        violation(latest, completedReply)?.let { throw IllegalArgumentException(it) }
        return completedReply
    }
}
