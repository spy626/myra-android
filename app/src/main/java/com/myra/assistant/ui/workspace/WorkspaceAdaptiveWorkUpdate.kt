package com.myra.assistant.ui.workspace

/**
 * Converts already-parsed task evidence into short live updates.
 *
 * This never invents hidden reasoning. Provider output is shown only as a proposal/review summary,
 * while commit/CI updates come from trusted connector receipts.
 */
internal object WorkspaceAdaptiveWorkUpdate {
    data class Update(
        val phase: WorkspaceWorkPhase,
        val label: String,
        val detail: String? = null,
    )

    private val internalNoise = Regex(
        "(?i)\\b(?:xkiro|groq|openrouter|qwen|gpt-oss|provider\\s+\\d*|task budget)\\b"
    )
    private val unsupportedSuccessClaim = Regex(
        "(?i)\\b(?:ci\\s*#?\\d*\\s*(?:passed|green)|build\\s+(?:passed|green|successful)|" +
            "tests?\\s+(?:passed|green|successful)|review\\s+(?:accepted|passed)|" +
            "merged?|deployed?|verified|completed)\\b"
    )

    fun scope(paths: List<String>): Update {
        require(paths.isNotEmpty() && paths.size <= WorkspaceGitHubSelfEditBatch.MAX_FILES) {
            "Adaptive work scope is outside the bounded file set"
        }
        val names = paths.map {
            WorkspaceGitHubWritePolicy.requirePath(it).substringAfterLast('/')
        }
        val label = if (names.size == 1) {
            "Scoped work to ${names.single()}"
        } else {
            "Scoped work across ${names.size} related files"
        }
        val detail = if (names.size == 1) {
            "Only the selected file is in this bounded change."
        } else {
            names.joinToString(" · ")
        }
        return Update(
            WorkspaceWorkPhase.THINKING,
            publicLabel(label),
            publicEvidence(detail),
        )
    }

    fun proposal(
        prepared: WorkspaceGitHubSelfEditBatch.Prepared,
        revised: Boolean = false,
        ciRepair: Boolean = false,
    ): Update {
        require(prepared.files.isNotEmpty() &&
            prepared.files.size <= WorkspaceGitHubSelfEditBatch.MAX_FILES) {
            "Adaptive proposal update is outside the bounded file set"
        }
        val label = when {
            ciRepair -> "Prepared a CI repair proposal"
            revised -> "Prepared the requested revision"
            else -> "Prepared a change proposal"
        }
        val fallback = prepared.files.joinToString(" · ") {
            WorkspaceGitHubWritePolicy.requirePath(it.path).substringAfterLast('/')
        }
        return Update(
            if (ciRepair || revised) WorkspaceWorkPhase.RECOVERING else WorkspaceWorkPhase.CODING,
            label,
            publicEvidence(prepared.rationale) ?: publicEvidence(fallback),
        )
    }

    fun review(
        review: WorkspaceGitHubPatchReviewer.Review,
        afterRevision: Boolean,
    ): Update {
        val label = when (review.decision) {
            WorkspaceGitHubPatchReviewer.Decision.ACCEPT ->
                if (afterRevision) "Second review accepted the revision"
                else "Review accepted the proposed change"
            WorkspaceGitHubPatchReviewer.Decision.REVISE ->
                if (afterRevision) "Second review still found an issue"
                else "Review found a fixable issue"
            WorkspaceGitHubPatchReviewer.Decision.REJECT -> "Review blocked the proposed change"
        }
        val summary = publicEvidence(review.summary)
        val risk = review.risks.asSequence().mapNotNull(::publicEvidence).firstOrNull()
        val detail = when {
            summary != null && risk != null && !summary.contains(risk, ignoreCase = true) ->
                publicEvidence("$summary Risk: $risk")
            summary != null -> summary
            else -> risk
        }
        return Update(WorkspaceWorkPhase.VERIFYING, label, detail)
    }

    fun committed(receipt: WorkspaceGitHubConnector.CommitReceipt): Update {
        require(receipt.files.isNotEmpty()) { "Committed update requires changed files" }
        val names = receipt.files.map {
            WorkspaceGitHubWritePolicy.requirePath(it).substringAfterLast('/')
        }
        val label = if (names.size == 1) {
            "Pushed ${names.single()} to the feature branch"
        } else {
            "Pushed ${names.size} changed files to the feature branch"
        }
        return Update(
            WorkspaceWorkPhase.VERIFYING,
            publicLabel(label),
            "Now verifying the exact pushed commit with CI.",
        )
    }

    fun ciRunning(runNumber: Long, status: String): Update {
        require(runNumber > 0L) { "CI run number is invalid" }
        val cleanStatus = WorkspaceWorkTrace.safeText(status, 32)
            .replace('_', ' ')
            .ifBlank { "running" }
        return Update(
            WorkspaceWorkPhase.VERIFYING,
            "CI #$runNumber is $cleanStatus",
            "Waiting for this exact commit to finish.",
        )
    }

    fun ciPassed(runNumber: Long): Update {
        require(runNumber > 0L) { "CI run number is invalid" }
        return Update(
            WorkspaceWorkPhase.VERIFYING,
            "CI #$runNumber passed for this commit",
            "Configured build/tests completed successfully.",
        )
    }

    fun ciFailed(runNumber: Long, summary: String): Update {
        require(runNumber > 0L) { "CI run number is invalid" }
        return Update(
            WorkspaceWorkPhase.RECOVERING,
            "CI #$runNumber failed; checking the cause",
            publicEvidence(summary),
        )
    }

    private fun publicLabel(raw: String): String =
        WorkspaceWorkTrace.safeText(raw, 86).ifBlank { "Working on the task" }

    private fun publicEvidence(raw: String): String? {
        val clean = WorkspaceWorkTrace.safeText(raw, 180).takeIf(String::isNotBlank) ?: return null
        if (WorkspaceSourceContext.containsPossibleSecret(clean)) return null
        if (internalNoise.containsMatchIn(clean)) return null
        if (unsupportedSuccessClaim.containsMatchIn(clean)) return null
        return clean
    }
}
