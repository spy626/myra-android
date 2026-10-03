package com.myra.assistant.ui.workspace

/** An explicit, local recheck of an already displayed context draft. Not send authorization. */
object WorkspaceContextFreshness {
    enum class Result { SAME_CONTENT_AND_SPEC, STALE_OR_BLOCKED }

    /** No stale source excerpt or secrets are returned in failure; only the current saved owner may read. */
    fun check(
        files: WorkspaceFileStore,
        tasks: WorkspaceTaskStore,
        projectId: String,
        earlier: WorkspaceSourceContext.Draft,
    ): Result {
        if (projectId != earlier.projectId) return Result.STALE_OR_BLOCKED
        return runCatching {
            val saved = requireNotNull(tasks.get(projectId))
            require(saved.taskId == earlier.taskId &&
                WorkspaceTaskContract.specToken(saved) == earlier.specToken &&
                WorkspaceTaskContract.isSpecApproved(saved) &&
                saved.status != WorkspaceTaskStatus.PAUSED)
            // prepare() rechecks the entire local file and screens secrets beyond the excerpt.
            val current = WorkspaceSourceContext.prepare(files, tasks, projectId, saved, earlier.path)
            if (current.fileSha256 == earlier.fileSha256 &&
                current.sourceExcerpt == earlier.sourceExcerpt &&
                current.truncated == earlier.truncated &&
                current.goal == earlier.goal &&
                current.acceptanceCriteria == earlier.acceptanceCriteria
            ) Result.SAME_CONTENT_AND_SPEC else Result.STALE_OR_BLOCKED
        }.getOrDefault(Result.STALE_OR_BLOCKED)
    }
}
