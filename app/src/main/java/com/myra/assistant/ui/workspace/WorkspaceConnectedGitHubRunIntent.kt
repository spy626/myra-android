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
        val latest: Boolean = false,
        val localError: String? = null,
        val includeCommitSha: Boolean = false,
    ) {
        init {
            require(listOf(runNumber != null, latest, localError != null).count { it } == 1) {
                "Connected GitHub run decision must be one exact run, latest run, or local error"
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
    private val latestCue = Regex(
        """(?iu)\b(?:latest|newest|most\s+recent|recent|current|abhi\s+ka|aaj\s+ka)\b"""
    )
    private val readRequest = Regex(
        """(?iu)\b(?:check|verify|read|fetch|show|get|tell|dekh\p{L}*|bata\p{L}*|kya|""" +
            """green|red|pass(?:ed)?|fail(?:ed)?|status|result|link|url|latest|newest|recent|current)\b|\?"""
    )
    private val explicitCommitSha = Regex(
        """(?iu)\b(?:commit\s+(?:sha|hash|id)|full\s+sha|sha\s+(?:bhi|also|too))\b"""
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
        if (values.size > 1) {
            return Decision(localError =
                "I found multiple build/run numbers. Ask me to verify one GitHub Actions run at a time; nothing was changed.")
        }
        if (values.size == 1) {
            return Decision(
                runNumber = values.single(),
                includeCommitSha = explicitCommitSha.containsMatchIn(requested),
            )
        }
        if (latestCue.containsMatchIn(requested)) {
            return Decision(
                latest = true,
                includeCommitSha = explicitCommitSha.containsMatchIn(requested),
            )
        }
        return null
    }

    /** Compact verified result, not a dump of connector metadata. */
    fun receipt(
        completion: WorkspaceConnectedGitHubRunRunner.Completion,
        includeCommitSha: Boolean = false,
    ): String {
        val run = completion.run
        val status = run.status.replace('_', ' ').replaceFirstChar { it.titlecase() }
        val result = run.conclusion?.replace('_', ' ')
            ?.replaceFirstChar { it.titlecase() }
            ?: if (run.status == "completed") "Unavailable" else "Pending"
        val headline = when {
            run.status != "completed" ->
                "Bro, **Build #${run.runNumber} abhi ${status.uppercase()} hai** ⏳"
            run.conclusion == "success" ->
                "Haan bro 😂💚 **Build #${run.runNumber} GREEN hai!**"
            run.conclusion == "failure" ->
                "Bro, **Build #${run.runNumber} FAILED hai** ❌"
            else -> "Bro, build #${run.runNumber} ka result **$result** hai."
        }
        return buildString {
            appendLine(headline)
            appendLine()
            appendLine("  • **Status:** $status" + if (run.status == "completed" && run.conclusion == "success") " ✅" else "")
            appendLine("  • **Result:** $result")
            appendLine("  • **Branch:** `${completion.branch}`")
            if (includeCommitSha) {
                appendLine()
                appendLine("Commit SHA:")
                appendLine("`${run.headSha}`")
            }
            appendLine()
            append("[🔗 GitHub Build #${run.runNumber} ↗](${run.url})")
        }
    }

    fun source(
        completion: WorkspaceConnectedGitHubRunRunner.Completion,
        observedAtMs: Long = System.currentTimeMillis(),
    ): WorkspaceVerifiedSourceStore.Source {
        val run = completion.run
        val status = run.status.replace('_', ' ')
        val result = run.conclusion?.replace('_', ' ') ?: "pending"
        return WorkspaceVerifiedSourceStore.Source(
            title = "GitHub Build #" + run.runNumber,
            url = run.url,
            snippet = "Connected branch " + completion.branch + " · " + status + " · " + result,
            observedAtMs = observedAtMs,
            verifiedLabel = "Connected GitHub",
        )
    }
}
