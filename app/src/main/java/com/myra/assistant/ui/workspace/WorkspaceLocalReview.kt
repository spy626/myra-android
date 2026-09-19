package com.myra.assistant.ui.workspace

/** Composes the existing safe readers into one local review action; never starts an AI worker. */
object WorkspaceLocalReview {
    data class Draft(
        val context: WorkspaceSourceContext.Draft,
        val plan: WorkspaceProjectPlanDraft.Draft,
    )

    /** One selected, project-confined file. All reads are local, with a final full-file freshness check. */
    fun prepare(
        files: WorkspaceFileStore,
        tasks: WorkspaceTaskStore,
        projectId: String,
        type: WorkspaceProjectType,
        expected: WorkspaceTask,
        selectedPath: String,
    ): Draft {
        val context = WorkspaceSourceContext.prepare(files, tasks, projectId, expected, selectedPath)
        val plan = WorkspaceProjectPlanDraft.prepare(files, tasks, projectId, type, context)
        require(WorkspaceContextFreshness.check(files, tasks, projectId, context) ==
            WorkspaceContextFreshness.Result.SAME_CONTENT_AND_SPEC) {
            "Project or spec changed while preparing the review. Select a fresh file"
        }
        return Draft(context, plan)
    }
}
