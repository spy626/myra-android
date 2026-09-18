package com.myra.assistant.ui.workspace

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.ai.ApiKeyStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Executes only an explicitly requested, bounded single-file edit through existing owners.
 * Send is the user's instruction to process that request; no unrelated files are shared.
 * Safe Edit keeps a protected rollback. Neither the model nor this class writes files directly.
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
    val isRunning: Boolean get() = request != null

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
        if (isRunning) { report("A coding request is already running."); return }
        val pending = runCatching { WorkspaceScopedEdit.pending(projects, id) }
            .getOrElse { error("Rollback needs attention: ${it.message}"); return }
        if (pending != null) {
            report("Previous edit has a protected rollback. Use Review edit · Undo / Keep in Chat first.")
            return
        }
        if (instruction.length > WorkspaceTaskContract.MAX_GOAL_LENGTH) {
            error("Coding request exceeds 500 characters. Shorten the instruction.")
            return
        }
        val key = runCatching { keys.get(ApiKeyStore.OPENROUTER) }
            .getOrElse { error("Secure provider key unavailable; nothing was shared."); return }
        if (key.isBlank()) {
            error("Coding request saved locally. Configure a free Workspace route in API & Cloud Settings.")
            return
        }
        // The explicit Send instruction authorizes only this bounded task and selected file.
        // All existing task/source freshness checks remain enforced by the canonical owners.
        runCatching {
            val existing = tasks.get(id)
            val criteria = existing?.acceptanceCriteria?.takeIf { it.isNotBlank() }
                ?: "The requested change is inspectable in the project files; existing work is preserved."
            val saved = tasks.create(id, instruction, replaceExisting = existing != null,
                rawAcceptanceCriteria = WorkspaceTaskContract.normalizeAcceptanceCriteria(criteria))
            tasks.setSpecificationApproved(id, saved.taskId, WorkspaceTaskContract.specToken(saved), true)
        }.onFailure { error("Task could not be saved: ${it.message}"); return }
        prepareSource(id, instruction, key)
    }

    private fun prepareSource(id: String, instruction: String, key: String) {
        if (!current(id)) return
        val project = projects.getProject(id) ?: return
        var paths = runCatching { WorkspaceSourceContext.choices(files, id) }
            .getOrElse { error("Source unavailable: ${it.message}"); return }
        if (paths.isEmpty()) {
            if (project.type != WorkspaceProjectType.WEBSITE) {
                error("No Android source exists. Add source in Project Files & Editor first.")
                return
            }
            runCatching { files.addWebsiteStarter(id) }
                .onFailure { error("Website starter could not be created: ${it.message}"); return }
            paths = runCatching { WorkspaceSourceContext.choices(files, id) }
                .getOrElse { error("Starter files unavailable: ${it.message}"); return }
        }
        val preferred = when {
            Regex("""(?i)\b(?:css|style|color|colour|background)\b""").containsMatchIn(instruction) -> "style.css"
            Regex("""(?i)\b(?:javascript|script|click|button|function)\b""").containsMatchIn(instruction) -> "script.js"
            else -> "index.html"
        }
        // One scoped file, never an entire project directory or an arbitrary provider choice.
        val selected = paths.firstOrNull { it == preferred } ?: paths.firstOrNull()
        if (selected == null) { error("No editable source file available."); return }
        val prepared = runCatching {
            WorkspaceAiHandoff.prepare(files, tasks, projects, id, selected, instruction)
        }.getOrElse { error("Source review blocked: ${it.message}"); return }
        send(id, key, prepared)
    }

    private fun send(id: String, key: String, prepared: WorkspaceAiHandoff.Draft) {
        if (isRunning || !current(id) || !WorkspaceAiHandoff.stillCurrent(files, tasks, projects, id, prepared)) {
            error("Project or source changed; request stopped. No source was sent.")
            return
        }
        val provider = WorkspaceChatGateway.Provider.OPENROUTER_FREE
        val messages = listOf(WorkspaceConversationStore.Message("explicit-one-file-prompt", "user",
            prepared.prompt, System.currentTimeMillis()))
        val outgoing = runCatching { WorkspaceChatGateway.request(provider, key, messages) }
            .getOrElse { error("Provider request refused: ${it.message}"); return }
        val serial = ++generation
        val call = WorkspaceChatGateway.client.newCall(outgoing)
        request = call
        report("Working on ${prepared.context.path} · Stop ■ to cancel. One-file Safe Edit only.")
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = complete(call, serial, id, prepared,
                Result.failure(IllegalStateException(WorkspaceFreeAiSuggestion.networkFailure(e))))
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
                error("Task or source changed while generating. No code was applied.")
                return@runOnUiThread
            }
            result.onSuccess { reply ->
                runCatching {
                    val draft = WorkspaceStructuredEdit.prepare(files, tasks, projects, id, reply)
                    require(draft.context.path == prepared.context.path &&
                        draft.context.fileSha256 == prepared.context.fileSha256 &&
                        draft.context.specToken == prepared.context.specToken) {
                        "AI proposal targeted a different file or outdated task"
                    }
                    suggestions.save(files, tasks, projects, id, reply, draft)
                    WorkspaceScopedEdit.apply(files, tasks, projects, draft.context, draft.proposal)
                }.onSuccess {
                    runCatching { suggestions.discard(id) }
                    report("One scoped file changed with protected rollback. Check Preview and use Undo / Keep in Chat. A complete website or verified build is not claimed.")
                }.onFailure { error("AI suggestion was not applied: ${it.message}. Check saved proposal and rollback in Chat.") }
            }.onFailure { error(it.message ?: "Provider failed; original files are unchanged.") }
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
        AlertDialog.Builder(activity).setTitle("Review saved code change")
            .setMessage(draft.displayText())
            .setNegativeButton("Later", null)
            .setPositiveButton("Apply saved change") { _, _ ->
                if (!current(id)) return@setPositiveButton
                runCatching { WorkspaceScopedEdit.apply(files, tasks, projects, draft.context, draft.proposal) }
                    .onSuccess {
                        runCatching { suggestions.discard(id) }
                        report("Applied one saved file edit with protected rollback. Check Preview and Undo / Keep in Chat.")
                    }.onFailure { error("Apply refused: ${it.message}. Check protected rollback in Chat.") }
            }.show()
    }

    fun reviewPending(id: String) {
        if (!current(id)) return
        val backup = runCatching { WorkspaceScopedEdit.pending(projects, id) }
            .getOrElse { error("Rollback data unavailable: ${it.message}"); return } ?: return
        AlertDialog.Builder(activity).setTitle("Review protected edit")
            .setMessage("File: ${backup.path}\nThis change has a saved rollback. Preview before choosing Keep. Undo will not overwrite newer manual edits. No visual/build pass is claimed.")
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
