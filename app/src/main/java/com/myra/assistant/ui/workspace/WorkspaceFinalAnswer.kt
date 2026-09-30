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
                append("Bro ✅ ")
                if (fileName != null) {
                    append("requested change `")
                    append(fileName)
                    append("` me complete ho gaya.")
                } else {
                    append("requested change ")
                    append(result.commit.files.size)
                    append(" files me complete ho gaya.")
                }
                append(" Exact CI #")
                append(result.workflow.runNumber)
                append(" ke configured build/tests pushed commit ke liye pass hue.")
                append(" Protected feature branch par hi write raha; merge nahi hua.")
                if (warning != null) {
                    appendLine()
                    appendLine()
                    append("Note: ")
                    append(warning)
                }
                appendLine()
                appendLine()
                append("Phone behavior ko CI verify nahi karta.")
            }.trim()
        } else {
            buildString {
                if (fileName != null) {
                    append("Requested change to `")
                    append(fileName)
                    append("` is complete.")
                } else {
                    append("Requested change across ")
                    append(result.commit.files.size)
                    append(" files is complete.")
                }
                append(" Exact CI #")
                append(result.workflow.runNumber)
                append(" passed the configured build/tests for the pushed commit.")
                append(" The protected feature-branch write stayed isolated; no merge was performed.")
                if (warning != null) {
                    appendLine()
                    appendLine()
                    append("Note: ")
                    append(warning)
                }
                appendLine()
                appendLine()
                append("CI does not verify physical phone behavior.")
            }.trim()
        }
    }
}