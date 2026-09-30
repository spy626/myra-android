package com.myra.assistant.ui.workspace

/**
 * Evidence-grounded final answers for completed work.
 *
 * This is presentation only: it does not call a model, inspect hidden reasoning, grant authority,
 * or turn CI into a phone-test claim. Every success statement comes from trusted completion
 * receipts that already passed the workflow verification gate.
 */
internal object WorkspaceFinalAnswer {
    private const val MAX_WARNING_CHARS = 320

    fun githubSuccess(result: WorkspaceGitHubSelfEditFlow.Completion): String {
        require(result.workflow.status == "completed" &&
            result.workflow.conclusion == "success") {
            "GitHub final answer requires completed successful CI"
        }
        require(result.workflow.headSha.equals(result.commit.commitSha, ignoreCase = true)) {
            "GitHub final answer CI SHA does not match the committed change"
        }
        require(result.commit.files.isNotEmpty()) {
            "GitHub final answer requires at least one changed file"
        }
        result.adaptiveAnswer?.let { adaptive ->
            return WorkspaceAdaptiveFinalAnswer.accept(adaptive, result)
        }

        result.pullRequest?.let { receipt ->
            require(receipt.draft && receipt.head == result.commit.branch) {
                "GitHub final answer PR does not match the protected branch"
            }
        }

        val changed = when (result.commit.files.size) {
            1 -> "Requested change to `" + result.commit.files.single().substringAfterLast('/') + "` is complete."
            else -> "Requested change across " + result.commit.files.size + " files is complete."
        }

        val warning = result.warning
            ?.let { WorkspaceWorkTrace.safeText(it, MAX_WARNING_CHARS) }
            ?.takeIf(String::isNotBlank)

        return buildString {
            append(changed)
            append(" Exact CI #")
            append(result.workflow.runNumber)
            append(" passed for the pushed commit.")
            append(" The protected feature-branch write stayed isolated; no merge was performed.")
            if (warning != null) {
                appendLine()
                appendLine()
                append("Note: ")
                append(warning)
            }
            appendLine()
            appendLine()
            append("CI verifies the configured build/tests, not physical phone behavior.")
        }.trim()
    }
}