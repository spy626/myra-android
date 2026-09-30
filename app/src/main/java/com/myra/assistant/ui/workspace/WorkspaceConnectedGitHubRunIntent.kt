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
    private val number = Regex("""(?<!\d)#?(\d{1,9})(?!\d)""")

    fun decide(message: String): Decision? {
        if (url.containsMatchIn(message) || nonBuildReference.containsMatchIn(message)) return null
        val proposal = WorkspaceSemanticTurnIntent.propose(message)
        if (proposal.kind != WorkspaceSemanticTurnIntent.Kind.READ_ONLY_VERIFICATION ||
            proposal.effect != WorkspaceSemanticTurnIntent.Effect.READ ||
            !buildCue.containsMatchIn(message)
        ) return null

        val values = number.findAll(message)
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
            append("GitHub Actions ")
            append(run.name)
            append(" #")
            append(run.runNumber)
            append(" is ")
            append(state)
            append(" on ")
            append(completion.branch)
            append(".")
            append("\nCommit: `")
            append(run.headSha.take(12))
            append("`.")
            append("\nRun: ")
            append(run.url)
            append("\nVerified read only — no repository change was made. ")
            append("This CI result does not prove physical phone-pass.")
        }
    }
}
