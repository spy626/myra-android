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

        val changed = when (result.commit.files.size) {
            1 -> "Updated `" + result.commit.files.single() + "` for the requested task."
            else -> buildString {
                append("Applied the requested change across ")
                append(result.commit.files.size)
                append(" files:")
                result.commit.files.take(5).forEach { path ->
                    append("\n• `")
                    append(path)
                    append("`")
                }
                if (result.commit.files.size > 5) {
                    append("\n• +")
                    append(result.commit.files.size - 5)
                    append(" more")
                }
            }
        }

        val pr = result.pullRequest?.let { receipt ->
            require(receipt.draft && receipt.head == result.commit.branch) {
                "GitHub final answer PR does not match the protected branch"
            }
            "Draft PR #" + receipt.number + " is updated; no merge was performed."
        } ?: "Draft PR update was not confirmed."

        val warning = result.warning
            ?.let { WorkspaceWorkTrace.safeText(it, MAX_WARNING_CHARS) }
            ?.takeIf(String::isNotBlank)

        return buildString {
            appendLine("✅ GitHub task complete")
            appendLine()
            appendLine("What changed: " + changed)
            appendLine()
            appendLine(
                "Verified: CI #" + result.workflow.runNumber + " GREEN · commit `" +
                    result.commit.commitSha.take(12) + "`."
            )
            appendLine(
                "Safety: write stayed on `" + result.commit.branch +
                    "`; main/master was not written or merged by this task."
            )
            appendLine(pr)
            if (warning != null) {
                appendLine()
                appendLine("Note: " + warning)
            }
            appendLine()
            append(
                "Next: test the changed behavior on your phone. CI verifies build/tests, " +
                    "not physical phone behavior."
            )
        }.trim()
    }
}