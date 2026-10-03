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

    private val hinglishTask = Regex(
        """(?iu)(?:[\u0900-\u097F]|\b(?:bro|bhai|karo|kro|karna|hai|hain|mein|mai|me|sirf|tak|batao|rakho|hatao|jodo)\b)"""
    )

    fun githubSuccess(
        result: WorkspaceGitHubSelfEditFlow.Completion,
        userTask: String? = null,
    ): String {
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

        val fileName = result.commit.files.singleOrNull()?.substringAfterLast('/')
        val useHinglish = userTask
            ?.takeIf { it.isNotBlank() && !WorkspaceSourceContext.containsPossibleSecret(it) }
            ?.let(hinglishTask::containsMatchIn)
            ?: false
        val warning = result.warning
            ?.let { WorkspaceWorkTrace.safeText(it, MAX_WARNING_CHARS) }
            ?.takeIf(String::isNotBlank)

        return if (useHinglish) {
            buildString {
                append("**Done bro ✅** ")
                if (fileName != null) {
                    append("`")
                    append(fileName)
                    append("` me requested change complete ho gaya.")
                } else {
                    append("Requested change ")
                    append(result.commit.files.size)
                    append(" files me complete ho gaya.")
                }
                appendLine()
                appendLine()
                append("CI #")
                append(result.workflow.runNumber)
                append(" GREEN hai — isi pushed commit ke configured build/tests pass hue. Main/master ko touch ya merge nahi kiya.")
                if (warning != null) {
                    appendLine()
                    appendLine()
                    append("Note: ")
                    append(warning)
                }
                appendLine()
                appendLine()
                append("Phone behavior ka final check physical phone test se hi hoga.")
            }.trim()
        } else {
            buildString {
                append("**Done ✅** ")
                if (fileName != null) {
                    append("The requested change to `")
                    append(fileName)
                    append("` is complete.")
                } else {
                    append("The requested change across ")
                    append(result.commit.files.size)
                    append(" files is complete.")
                }
                appendLine()
                appendLine()
                append("CI #")
                append(result.workflow.runNumber)
                append(" is GREEN — the configured build/tests passed for this pushed commit. Main/master was not written or merged.")
                if (warning != null) {
                    appendLine()
                    appendLine()
                    append("Note: ")
                    append(warning)
                }
                appendLine()
                appendLine()
                append("Phone behavior still needs a physical phone test.")
            }.trim()
        }
    }
}