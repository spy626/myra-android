package com.myra.assistant.ui.workspace

import org.json.JSONObject

/** Strict model-style edit envelope. It never writes files or calls a provider.
 * The current task, file, privacy scan and source fingerprint are independently re-established locally.
 */
object WorkspaceStructuredEdit {
    private const val MAX_JSON_CHARS = 6_000
    private const val MAX_RATIONALE_CHARS = 500
    private val allowedKeys = setOf("schemaVersion", "operation", "path", "oldText", "newText", "rationale")

    data class Draft(
        val context: WorkspaceSourceContext.Draft,
        val proposal: WorkspaceScopedEdit.Proposal,
        val rationale: String,
    ) {
        fun displayText(): String = buildString {
            appendLine("STRUCTURED EDIT DRAFT — UNTRUSTED INPUT, NOT APPLIED")
            appendLine("File: ${proposal.path}")
            appendLine("Current source SHA-256: ${proposal.baseSha256}")
            appendLine("Expected result SHA-256: ${proposal.resultSha256}")
            appendLine("Goal: ${context.goal}")
            appendLine("Acceptance criteria: ${context.acceptanceCriteria}")
            appendLine()
            appendLine("Replace exactly once:")
            appendLine(proposal.oldText)
            appendLine()
            appendLine("With:")
            appendLine(proposal.newText)
            appendLine()
            appendLine("Untrusted rationale: ${rationale.ifBlank { "(none)" }}")
            append("Local validation only. No provider was called. Separate file-write approval and rollback are still required.")
        }
    }

    fun prepare(
        files: WorkspaceFileStore,
        tasks: WorkspaceTaskStore,
        projects: WorkspaceProjectStore,
        projectId: String,
        rawJson: String,
    ): Draft {
        require(rawJson.isNotBlank() && rawJson.length <= MAX_JSON_CHARS) {
            "Structured edit JSON must be 1..$MAX_JSON_CHARS characters"
        }
        require(WorkspaceScopedEdit.pending(projects, projectId) == null) {
            "Finish the previous edit: Undo or Keep change before importing another patch"
        }
        val task = requireNotNull(tasks.get(projectId)) { "Save a task brief first" }
        require(WorkspaceTaskContract.isSpecApproved(task) && task.status != WorkspaceTaskStatus.PAUSED) {
            "Approve and resume the saved specification before importing an edit"
        }

        val json = runCatching { JSONObject(rawJson) }
            .getOrElse { throw IllegalArgumentException("Structured edit must be valid JSON") }
        require(json.keys().asSequence().toSet().all { it in allowedKeys }) {
            "Structured edit contains unsupported fields"
        }
        require(json.optInt("schemaVersion", -1) == 1) { "Unsupported structured edit schema" }
        require(json.optString("operation") == "replace_exact_once") {
            "Only replace_exact_once is allowed in this trial"
        }

        val path = json.optString("path").trim()
        val oldText = json.optString("oldText")
        val newText = json.optString("newText")
        val rationale = json.optString("rationale").trim()
        require(path.isNotBlank()) { "Structured edit path is required" }
        require(rationale.length <= MAX_RATIONALE_CHARS && rationale.none { it.isISOControl() }) {
            "Rationale must be 500 characters or fewer"
        }
        require(path in WorkspaceSourceContext.choices(files, projectId)) {
            "Structured edit targeted a blocked or unavailable project file"
        }

        // Rebuild trusted local context from the current project. Never trust a model-provided hash/spec token.
        val context = WorkspaceSourceContext.prepare(files, tasks, projectId, task, path)
        val proposal = WorkspaceScopedEdit.propose(files, tasks, projects, context, oldText, newText)
        return Draft(context, proposal, rationale)
    }
}
