package com.myra.assistant.ui.workspace

import java.util.Locale

/**
 * Deterministic local execution plan and completion gate for protected GitHub coding.
 * It is local runtime execution guidance, not another agent/memory owner and never grants tool authority.
 */
internal object WorkspaceGitHubCodingPlan {
    private const val MAX_GOAL_CHARS = 2_000

    data class Plan(
        val goal: String,
        val repository: String,
        val branch: String,
        val selectedPaths: List<String>,
        val expectedChange: String,
        val completionCriteria: List<String>,
    ) {
        fun traceSummary(): String =
            selectedPaths.size.toString() +
                " file(s) · requested behavior only · exact CI GREEN required"
    }

    fun create(
        goal: String,
        repository: String,
        branch: String,
        selectedPaths: List<String>,
    ): Plan {
        val cleanGoal = goal.trim().replace(Regex("""\s+"""), " ")
        require(cleanGoal.length in 1..MAX_GOAL_CHARS && cleanGoal.none(Char::isISOControl)) {
            "GitHub coding plan goal is invalid"
        }
        val binding = WorkspaceConnectorPolicy.binding(repository, branch)
        require(binding.branch == "agent/myra-phase-1") {
            "GitHub coding plan must stay on the protected feature branch"
        }
        require(selectedPaths.size in 1..WorkspaceGitHubSelfEditBatch.MAX_FILES) {
            "GitHub coding plan file count is outside the LYRA bound"
        }
        val paths = selectedPaths.map(WorkspaceGitHubWritePolicy::requirePath)
        require(paths.map { it.lowercase(Locale.US) }.toSet().size == paths.size) {
            "GitHub coding plan contains duplicate paths"
        }
        return Plan(
            goal = cleanGoal,
            repository = binding.repository,
            branch = binding.branch,
            selectedPaths = paths,
            expectedChange =
                "Apply only the explicit user-requested behavior inside the selected file scope.",
            completionCriteria = listOf(
                "Every committed path must stay inside the locally selected scope.",
                "The protected feature-branch receipt must match the intended repository and branch.",
                "Build Android APK must complete successfully for the exact committed SHA.",
                "Completion must come from tool evidence, never a provider claim.",
            ),
        )
    }

    fun verifyCompletion(
        plan: Plan,
        receipt: WorkspaceGitHubConnector.CommitReceipt,
        workflow: WorkspaceGitHubConnector.WorkflowRun,
    ) {
        require(receipt.repository.equals(plan.repository, ignoreCase = true) &&
            receipt.branch == plan.branch) {
            "Completion receipt does not match the coding plan repository binding"
        }
        require(receipt.files.isNotEmpty() &&
            receipt.files.all { changed ->
                plan.selectedPaths.any { selected -> selected.equals(changed, ignoreCase = true) }
            }) {
            "Completion receipt contains a path outside the coding plan"
        }
        require(workflow.name == "Build Android APK" &&
            workflow.headSha.equals(receipt.commitSha, ignoreCase = true) &&
            workflow.status == "completed" &&
            workflow.conclusion == "success") {
            "Exact-SHA GitHub Actions verification did not satisfy completion criteria"
        }
    }
}
