package com.myra.assistant.ui.workspace

/** A read-only, deterministic plan outline derived from one approved spec and one local source snapshot.
 *  This is not a model-generated implementation proposal, user-approved plan, or execution evidence.
 */
object WorkspaceProjectPlanDraft {
    data class Draft(
        val projectId: String,
        val taskId: String,
        val specToken: String,
        val sourcePath: String,
        val sourceSha256: String,
        val goal: String,
        val acceptanceCriteria: String,
        val steps: List<WorkspacePlanStep>,
    ) {
        fun displayText(): String = buildString {
            appendLine("LOCAL PLAN OUTLINE — FOR REVIEW ONLY")
            appendLine("Template based on your saved spec; NOT an AI-generated solution.")
            appendLine("Project: $projectId")
            appendLine("Goal: $goal")
            appendLine("Acceptance criteria: $acceptanceCriteria")
            appendLine("Candidate file to inspect: $sourcePath")
            appendLine("Full-file snapshot SHA-256: $sourceSha256")
            appendLine("Other affected files: unknown until real project analysis.")
            appendLine()
            steps.forEachIndexed { index, step ->
                appendLine("${index + 1}. ${step.intent}")
                appendLine("   Lane: ${step.lane.name.lowercase().replace('_', ' ')}")
                appendLine("   ${if (step.approvalRequired) "Separate action approval required" else "Trusted evidence required"}")
            }
            appendLine()
            append("Draft only. No model request, file edit, build, plan approval or verification occurred. Recheck source/spec before future use.")
        }
    }

    fun prepare(
        files: WorkspaceFileStore,
        tasks: WorkspaceTaskStore,
        projectId: String,
        type: WorkspaceProjectType,
        context: WorkspaceSourceContext.Draft,
    ): Draft {
        require(WorkspaceContextFreshness.check(files, tasks, projectId, context) ==
            WorkspaceContextFreshness.Result.SAME_CONTENT_AND_SPEC) {
            "Context changed or was blocked. Prepare a new local context before planning"
        }
        val saved = requireNotNull(tasks.get(projectId)) { "Saved task unavailable" }
        require(saved.taskId == context.taskId &&
            WorkspaceTaskContract.specToken(saved) == context.specToken &&
            WorkspaceTaskContract.isSpecApproved(saved) &&
            saved.status != WorkspaceTaskStatus.PAUSED) {
            "Saved spec was changed, paused or approval revoked"
        }
        // Do not embed untrusted source text or claim the source file is definitely an edit target.
        return Draft(projectId, saved.taskId, context.specToken, context.path,
            context.fileSha256, saved.goal, saved.acceptanceCriteria,
            WorkspaceTaskContract.steps(type))
    }
}
