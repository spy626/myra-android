package com.myra.assistant.ui.workspace

/** A user's screen-only clarification of ONE current plan outline; never an approved edit or AI instruction. */
data class WorkspacePlanReviewNote(
    val projectId: String,
    val taskId: String,
    val specToken: String,
    val sourcePath: String,
    val sourceSha256: String,
    val note: String,
) {
    fun displayText(): String = buildString {
        appendLine("YOUR PLAN REVIEW NOTE — SCREEN ONLY / NOT SAVED")
        appendLine("File to review: $sourcePath")
        appendLine("Full-file SHA-256: $sourceSha256")
        appendLine("Requested focus: $note")
        append("Planning feedback only. No AI request, plan approval, file edit, build or verification. Copy your note before leaving this screen.")
    }

    companion object {
        private const val MAX_NOTE_CHARS = 500
        private val credentialAssignment = Regex(
            "(?i)\\b(?:api[ _-]?key|password|passwd|client[ _-]?secret|private[ _-]?key|access[ _-]?token|refresh[ _-]?token)\\b\\s*[:=]"
        )
        private val keyMaterial = Regex("(?i)-----BEGIN [^-]*PRIVATE KEY-----|\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,})\\b")

        /** Fail closed if the plan, saved spec, source hash or planning approval is no longer current. */
        fun prepare(
            files: WorkspaceFileStore,
            tasks: WorkspaceTaskStore,
            projectId: String,
            type: WorkspaceProjectType,
            context: WorkspaceSourceContext.Draft,
            reviewedPlan: WorkspaceProjectPlanDraft.Draft,
            rawNote: String,
        ): WorkspacePlanReviewNote {
            val freshPlan = WorkspaceProjectPlanDraft.prepare(files, tasks, projectId, type, context)
            require(freshPlan == reviewedPlan) { "Plan or source changed; prepare and review a fresh plan" }
            val note = rawNote.trim().replace(Regex("\\s+"), " ")
            require(note.isNotEmpty() && note.length <= MAX_NOTE_CHARS && note.none { it.isISOControl() }) {
                "Enter a review note of 1–500 characters"
            }
            require(!credentialAssignment.containsMatchIn(note) && !keyMaterial.containsMatchIn(note)) {
                "Possible secret in review note; remove it before continuing"
            }
            return WorkspacePlanReviewNote(
                freshPlan.projectId, freshPlan.taskId, freshPlan.specToken,
                freshPlan.sourcePath, freshPlan.sourceSha256, note,
            )
        }
    }
}
