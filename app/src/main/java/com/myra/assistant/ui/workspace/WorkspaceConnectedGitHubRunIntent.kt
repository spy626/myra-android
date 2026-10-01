package com.myra.assistant.ui.workspace

/**
 * Resolves a recent GitHub Actions run number only from a read-only latest turn.
 *
 * This is intentionally narrower than general reference resolution. URLs, PRs/issues, write turns,
 * missing numbers and ambiguous multiple numbers are left to their existing routes.
 */
internal object WorkspaceConnectedGitHubRunIntent {
    data class Decision(
        val runNumber: Long? = null,
        val localError: String? = null,
    ) {
        init {
            require((runNumber == null) xor (localError == null)) {
                "Connected GitHub run decision must be one run number or one local error"
            }
        }
    }

    private val url = Regex("""https?://""", RegexOption.IGNORE_CASE)
    private val nonBuildReference = Regex(
        """(?iu)\b(?:pull\s+request|pr|issue)\b"""
    )
    private val buildCue = Regex(
        """(?iu)\b(?:build|run|workflow|ci|green|red|pass(?:ed)?|fail(?:ed)?|status|result)\b"""
    )
    // Numeric build identifiers must never be extracted from a SHA, version or branch token.
    private val number = Regex("""(?<![\p{L}\d_./-])#?(\d{1,9})(?![\p{L}\d_/-]|\.\d)""")
    private val statusQuestion = Regex(
        """(?iu)\b(?:green|red|pass(?:ed)?|fail(?:ed)?|status|result|ci)\b"""
    )
    private val readRequest = Regex(
        """(?iu)\b(?:check|verify|read|fetch|show|get|tell|dekh\p{L}*|bata\p{L}*|kya|green|red|pass(?:ed)?|fail(?:ed)?|status|result)\b|\?"""
    )
    private val constraintBoundary = Regex(
        """(?iu)(?:\b(?:do\s+not|don't|dont|never|without)\b|\b(?:kuch\s+change\s+mat)\b)"""
    )

    fun decide(message: String): Decision? {
        if (url.containsMatchIn(message) || nonBuildReference.containsMatchIn(message)) return null
        // Negated actions are preservation constraints, not part of the thing to look up.
        // E.g. "read HEAD SHA; do not start build #3372" is a HEAD request, not a run lookup.
        val requested = constraintBoundary.find(message)?.let {
            message.substring(0, it.range.first)
        } ?: message
        val proposal = WorkspaceSemanticTurnIntent.propose(message)
        if (proposal.effect == WorkspaceSemanticTurnIntent.Effect.WRITE ||
            proposal.kind == WorkspaceSemanticTurnIntent.Kind.CAPABILITY_QUERY ||
            proposal.kind == WorkspaceSemanticTurnIntent.Kind.ACTION_REQUEST ||
            !(buildCue.containsMatchIn(requested) ||
                statusQuestion.containsMatchIn(message)) ||
            !(readRequest.containsMatchIn(requested) ||
                statusQuestion.containsMatchIn(message))
        ) return null

        val values = number.findAll(requested)
            .mapNotNull { it.groupValues.getOrNull(1)?.toLongOrNull() }
            .filter { it > 0L }
            .distinct()
            .toList()
        if (values.isEmpty()) return null
        if (values.size != 1) {
            return Decision(localError =
                "I found multiple build/run numbers. Ask me to verify one GitHub Actions run at a time; nothing was changed.")
        }
        return Decision(runNumber = values.single())
    }

    fun receipt(completion: WorkspaceConnectedGitHubRunRunner.Completion): String {
        val run = completion.run
        val state = when {
            run.status != "completed" -> run.status.uppercase()
            run.conclusion == "success" -> "GREEN"
            run.conclusion.isNullOrBlank() -> "COMPLETED"
            else -> run.conclusion.uppercase()
        }
        return buildString {
            append("Bro, build #")
            append(run.runNumber)
            append(" ka status **")
            append(state)
            append("** hai")
            append(if (state == "GREEN") " ✅" else if (state == "FAILURE") " ❌" else "")
            append(".")
            append("\nCommit SHA: `")
            append(run.headSha)
            append("`")
            append("\nDetails: ")
            append(run.url)
        }
    }
}
