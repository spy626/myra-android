package com.myra.assistant.ui.workspace

import android.content.Intent
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.ui.settings.ApiCloudSettingsActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Chat-only UI coordinator for the EXISTING task, source, gateway and Safe Edit owners.
 * It neither creates a second model client nor gives a provider direct file-write authority.
 * The current coding engine proposes exactly ONE existing-file edit per reviewed request.
 */
internal class WorkspaceChatCodingFlow(
    private val activity: AppCompatActivity,
    private val projects: WorkspaceProjectStore,
    private val files: WorkspaceFileStore,
    private val tasks: WorkspaceTaskStore,
    private val suggestions: WorkspaceAiSuggestionDraftStore,
    private val keys: ApiKeyStore,
    private val activeProject: () -> String?,
    private val report: (String) -> Unit,
) {
    private var generation = 0L
    private var request: Call? = null

    fun cancel() {
        generation++
        request?.cancel()
        request = null
    }

    private fun error(message: String) = report(message)
    private fun current(id: String) = activeProject() == id && projects.getProject(id) != null

    fun continueRequest(id: String, instruction: String) {
        if (!current(id)) return
        val project = projects.getProject(id) ?: return
        if (project.type == WorkspaceProjectType.CHAT) return
        if (request != null) { report("A coding request is already running."); return }
        val pending = runCatching { WorkspaceScopedEdit.pending(projects, id) }
            .getOrElse { error("Rollback needs attention: ${it.message}"); return }
        if (pending != null) { reviewPending(id); return }
        val existing = tasks.get(id)
        if (existing != null && existing.goal == instruction &&
            WorkspaceTaskContract.isSpecApproved(existing) && existing.status != WorkspaceTaskStatus.PAUSED) {
            prepareSource(id, instruction)
            return
        }
        if (instruction.length > WorkspaceTaskContract.MAX_GOAL_LENGTH) {
            error("Coding request exceeds 500 characters. Shorten it to approve the project scope.")
            return
        }
        // The user reviews the exact goal AND editable acceptance criteria in Chat. This is
        // planning consent only, never provider or file-write consent.
        val criteria = EditText(activity).apply {
            setSingleLine(false)
            maxLines = 4
            setText(existing?.acceptanceCriteria?.ifBlank { null }
                ?: "The requested change can be inspected in the project files and preview; existing work is preserved.")
            filters = arrayOf(android.text.InputFilter.LengthFilter(WorkspaceTaskContract.MAX_ACCEPTANCE_LENGTH))
        }
        AlertDialog.Builder(activity).setTitle("Review coding task")
            .setMessage("Project: ${project.name}\nGoal: $instruction\n\nEdit the acceptance criteria below. Approving the scope does NOT send data or edit files.")
            .setView(criteria)
            .setNegativeButton("Not now", null)
            .setPositiveButton("Approve task") { _, _ ->
                if (!current(id)) return@setPositiveButton
                runCatching {
                    val criterion = WorkspaceTaskContract.normalizeAcceptanceCriteria(criteria.text.toString())
                    require(criterion.isNotBlank()) { "Acceptance criteria cannot be empty" }
                    val saved = tasks.create(id, instruction, replaceExisting = tasks.get(id) != null,
                        rawAcceptanceCriteria = criterion)
                    tasks.setSpecificationApproved(id, saved.taskId, WorkspaceTaskContract.specToken(saved), true)
                }.onSuccess { prepareSource(id, instruction) }
                    .onFailure { error("Task review failed: ${it.message}") }
            }.show()
    }

    private fun prepareSource(id: String, instruction: String) {
        if (!current(id)) return
        val project = projects.getProject(id) ?: return
        val paths = runCatching { WorkspaceSourceContext.choices(files, id) }
            .getOrElse { error("Source unavailable: ${it.message}"); return }
        if (paths.isEmpty()) {
            if (project.type != WorkspaceProjectType.WEBSITE) {
                error("No Android source file exists. Open Project Files & Editor; LYRA cannot claim an Android build without source.")
                return
            }
            AlertDialog.Builder(activity).setTitle("Create website starter files?")
                .setMessage("This empty project needs index.html, style.css and script.js before the existing one-file AI editor can work. These are starter files, NOT a finished website. Create them locally without overwriting any existing file?")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Create starter") { _, _ ->
                    if (!current(id)) return@setPositiveButton
                    runCatching { files.addWebsiteStarter(id) }
                        .onSuccess { prepareSource(id, instruction) }
                        .onFailure { error("No starter created: ${it.message}") }
                }.show()
            return
        }
        val preferred = when {
            Regex("""(?i)\b(?:css|style|color|colour|background)\b""").containsMatchIn(instruction) -> "style.css"
            Regex("""(?i)\b(?:javascript|script|click|button|function)\b""").containsMatchIn(instruction) -> "script.js"
            else -> "index.html"
        }
        val selected = paths.firstOrNull { it == preferred } ?: paths.first()
        if (paths.size == 1) previewSource(id, instruction, selected)
        else AlertDialog.Builder(activity).setTitle("Choose file for this one edit")
            .setMessage("The current Safe Edit engine can edit one reviewed file per request. No automatic multi-file generation is available yet.")
            .setSingleChoiceItems(paths.toTypedArray(), paths.indexOf(selected)) { dialog, index ->
                dialog.dismiss()
                previewSource(id, instruction, paths[index])
            }.setNegativeButton("Cancel", null).show()
    }

    private fun previewSource(id: String, instruction: String, path: String) {
        if (!current(id)) return
        val prepared = runCatching {
            WorkspaceAiHandoff.prepare(files, tasks, projects, id, path, instruction)
        }.getOrElse { error("Source review blocked: ${it.message}"); return }
        val key = runCatching { keys.get(ApiKeyStore.OPENROUTER) }
            .getOrElse { error("Secure provider key unavailable; nothing was shared."); return }
        if (key.isBlank()) {
            report("Coding request saved locally. Add a verified-free Workspace provider key; no source was sent.")
            AlertDialog.Builder(activity).setTitle("No free provider configured")
                .setMessage("Configure an OpenRouter key first. Gemini is reserved for Voice. No request or paid fallback will occur.")
                .setNegativeButton("Close", null)
                .setPositiveButton("API settings") { _, _ ->
                    activity.startActivity(Intent(activity, ApiCloudSettingsActivity::class.java))
                }.show()
            return
        }
        AlertDialog.Builder(activity).setTitle("Share this file with OpenRouter?")
            .setMessage(prepared.displayText().take(7000) +
                "\n\nOpenRouter's $0 free-model router may have no available free quota. No paid fallback. This approval sends ONLY the displayed project task, instruction and selected source excerpt, not the project directory. Continue once?")
            .setNegativeButton("Keep local", null)
            .setPositiveButton("Send once") { _, _ ->
                if (current(id)) send(id, key, prepared)
            }.show()
    }

    private fun send(id: String, key: String, prepared: WorkspaceAiHandoff.Draft) {
        if (request != null || !current(id) || !WorkspaceAiHandoff.stillCurrent(files, tasks, projects, id, prepared)) {
            error("Project or source changed; reopen the coding request. Nothing was sent.")
            return
        }
        val provider = WorkspaceChatGateway.Provider.OPENROUTER_FREE
        val messages = listOf(WorkspaceConversationStore.Message("approved-one-file-prompt", "user",
            prepared.prompt, System.currentTimeMillis()))
        val outgoing = runCatching { WorkspaceChatGateway.request(provider, key, messages) }
            .getOrElse { error("Provider request refused: ${it.message}"); return }
        val serial = ++generation
        val call = WorkspaceChatGateway.client.newCall(outgoing)
        request = call
        report("One approved source request in progress; no source write authorized.")
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = complete(call, serial, id, prepared,
                Result.failure(IllegalStateException("Free route failed or was cancelled. No automatic retry or paid fallback.")))
            override fun onResponse(call: Call, response: Response) = complete(call, serial, id,
                prepared, runCatching { WorkspaceChatGateway.read(provider, response) })
        })
    }

    private fun complete(call: Call, serial: Long, id: String, prepared: WorkspaceAiHandoff.Draft,
                         result: Result<String>) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== call || !current(id)) return@runOnUiThread
            request = null
            if (!WorkspaceAiHandoff.stillCurrent(files, tasks, projects, id, prepared)) {
                error("Task or source changed during the request. No code applied.")
                return@runOnUiThread
            }
            result.onSuccess { reply ->
                runCatching {
                    val draft = WorkspaceStructuredEdit.prepare(files, tasks, projects, id, reply)
                    require(draft.context.path == prepared.context.path &&
                        draft.context.fileSha256 == prepared.context.fileSha256 &&
                        draft.context.specToken == prepared.context.specToken) {
                        "AI proposal targeted a different file or an outdated task"
                    }
                    suggestions.save(files, tasks, projects, id, reply, draft)
                    draft
                }.onSuccess { reviewProposal(id, it) }
                    .onFailure { error("AI suggestion rejected by local checks: ${it.message}. No files changed.") }
            }.onFailure { error(it.message ?: "Provider failed; no file changed.") }
        }
    }

    fun reviewSaved(id: String) {
        if (!current(id)) return
        val recovered = runCatching { suggestions.recover(files, tasks, projects, id) }
            .getOrElse { error("Saved proposal unavailable: ${it.message}"); return }
        if (recovered is WorkspaceAiSuggestionDraftStore.Recovery.Ready) reviewProposal(id, recovered.draft)
        else error("No current saved proposal is available; request a fresh edit.")
    }

    private fun reviewProposal(id: String, draft: WorkspaceStructuredEdit.Draft) {
        if (!current(id)) return
        AlertDialog.Builder(activity).setTitle("Review code change — not yet applied")
            .setMessage(draft.displayText())
            .setNegativeButton("Keep for later", null)
            .setPositiveButton("Apply this one file") { _, _ ->
                if (!current(id)) return@setPositiveButton
                runCatching {
                    WorkspaceScopedEdit.apply(files, tasks, projects, draft.context, draft.proposal)
                }.onSuccess {
                    runCatching { suggestions.discard(id) }
                    report("Applied one approved file edit and checked its saved SHA. Preview is NOT yet verified. Undo or Keep in Chat.")
                    reviewPending(id)
                }.onFailure { error("Apply refused: ${it.message}. Check the protected rollback in Chat.") }
            }.show()
    }

    fun reviewPending(id: String) {
        if (!current(id)) return
        val backup = runCatching { WorkspaceScopedEdit.pending(projects, id) }
            .getOrElse { error("Rollback data unavailable: ${it.message}"); return } ?: return
        AlertDialog.Builder(activity).setTitle("Review protected edit")
            .setMessage("File: ${backup.path}\nThe edit has a saved rollback. Preview it before choosing Keep. Undo will refuse to overwrite newer manual changes. No browser build or visual verification is claimed.")
            .setNegativeButton("Undo") { _, _ ->
                runCatching { WorkspaceScopedEdit.undo(files, projects, id) }
                    .onSuccess { report("Original file restored and locally verified.") }
                    .onFailure { error("Undo refused: ${it.message}") }
            }.setNeutralButton("Later", null)
            .setPositiveButton("Keep") { _, _ ->
                runCatching { WorkspaceScopedEdit.keep(files, projects, id) }
                    .onSuccess { report("Protected edit kept. No preview/build pass claimed.") }
                    .onFailure { error("Keep refused: ${it.message}") }
            }.show()
    }
}
