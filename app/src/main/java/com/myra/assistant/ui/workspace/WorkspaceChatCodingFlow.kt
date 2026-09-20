package com.myra.assistant.ui.workspace

import android.content.Context
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
    private val onCompleted: (String, String, String) -> Unit,
) {
    private var generation = 0L
    private var request: Call? = null
    private var activeTurn: Pair<String, String>? = null
    val isRunning: Boolean get() = request != null

    fun cancel() {
        generation++
        request?.cancel()
        request = null
        activeTurn = null
    }

    /** Source writes are finished before the result enters the private transcript. */
    private fun terminal(message: String, status: String = message) {
        val turn = activeTurn
        activeTurn = null
        val result = if (turn == null) Result.success(Unit)
            else runCatching { onCompleted(turn.first, turn.second, message) }
        if (result.isFailure) report("Coding result could not be saved in Chat: " +
            "${result.exceptionOrNull()?.message}. Check Work files before retrying.")
        else report(status)
    }
    private fun error(message: String) = terminal(WorkspaceCodingResult.failure(message), message)
    private fun current(id: String) = activeProject() == id && projects.getProject(id) != null

    fun continueRequest(id: String, instruction: String, userMessageId: String? = null) {
        if (!current(id)) return
        val project = projects.getProject(id) ?: return
        if (project.type == WorkspaceProjectType.CHAT) return
        if (isRunning) { report("A coding request is already running."); return }
        activeTurn = userMessageId?.let { id to it }
        if (project.type == WorkspaceProjectType.WEBSITE) {
            continueWebsite(id, instruction)
            return
        }
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

    /** Explicit 'build website' Send authorizes generation of the three named project files.
     * The AI proposes text; the local file owner checks snapshots and saves a durable Undo.
     * No additional per-file permission popup is needed for this requested website build.
     */
    private fun continueWebsite(id: String, instruction: String) {
        if (instruction.length > WorkspaceTaskContract.MAX_GOAL_LENGTH) {
            error("Website request exceeds 500 characters; shorten it without removing important details.")
            return
        }
        val key = runCatching { keys.get(ApiKeyStore.OPENROUTER) }
            .getOrElse { error("Secure OpenRouter key unavailable; nothing was shared."); return }
        if (key.isBlank()) {
            error("Website request saved locally. Add an OpenRouter Free key in API & Cloud Settings.")
            return
        }
        runCatching { WorkspaceWebsiteGeneration.finishPreviousForNewRequest(files, projects, id) }
            .onFailure { error("Previous edit needs attention: ${it.message}. Use Undo in Chat."); return }
        runCatching {
            val previous = tasks.get(id)
            val criteria = previous?.acceptanceCriteria?.takeIf { it.isNotBlank() }
                ?: "The three saved website files are inspectable in Work Preview; preserve existing work."
            val saved = tasks.create(id, instruction, replaceExisting = previous != null,
                rawAcceptanceCriteria = criteria)
            tasks.setSpecificationApproved(id, saved.taskId, WorkspaceTaskContract.specToken(saved), true)
        }.onFailure { error("Website task could not be saved: ${it.message}"); return }
        val snapshot = runCatching { WorkspaceWebsiteGeneration.prepare(files, tasks, projects, id) }
            .getOrElse { error("Website source review blocked: ${it.message}"); return }
        val outgoing = runCatching { WorkspaceWebsiteGeneration.request(key, snapshot) }
            .getOrElse { error("Free website request refused: ${it.message}"); return }
        val serial = ++generation
        val call = WorkspaceWebsiteGeneration.client.newCall(outgoing)
        request = call
        report("Building index.html, style.css and script.js in this project · Stop ■ to cancel.")
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val message = if (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException)
                    "Website provider timed out (up to 80 seconds). No incomplete code was saved."
                else "Website provider connection failed; no result received. No paid fallback."
                completeWebsite(call, serial, id, snapshot,
                    Result.failure(IllegalStateException(message)))
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.code == 429) {
                    // Only a definitive final HTTP rejection can trigger another provider.
                    // Close the first response before attempting the separately consented resend.
                    response.close()
                    fallbackWebsiteOn429(call, serial, id, snapshot)
                    return
                }
                completeWebsite(call, serial, id, snapshot,
                    runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
            }
        })
    }

    private fun fallbackWebsiteOn429(first: Call, serial: Long, id: String,
                                     snapshot: WorkspaceWebsiteGeneration.Snapshot) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== first || !current(id)) return@runOnUiThread
            val preferences = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            val optedIn = preferences.getBoolean(WorkspaceWebsiteGroqFallback.PREFERENCE_KEY, false)
            val groqFree = preferences.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
            val key = runCatching { keys.get(ApiKeyStore.GROQ) }.getOrDefault("")
            if (!WorkspaceWebsiteGroqFallback.eligible(429, optedIn, groqFree, key)) {
                completeWebsite(first, serial, id, snapshot, Result.failure(
                    IllegalStateException("OpenRouter Free HTTP 429. Website Groq fallback is unavailable or OFF. " +
                        "To enable a one-time automatic switch, save a Groq Free key and enable " +
                        "Groq Free/ZDR plus the separate website-source fallback setting. No paid fallback.")))
                return@runOnUiThread
            }
            val secondRequest = runCatching { WorkspaceWebsiteGroqFallback.request(key, snapshot) }
                .getOrElse { issue ->
                    completeWebsite(first, serial, id, snapshot, Result.failure(
                        IllegalStateException("OpenRouter 429; Groq Free website fallback not sent: " +
                            "${issue.message}. No files changed.")))
                    return@runOnUiThread
                }
            val second = WorkspaceWebsiteGroqFallback.client.newCall(secondRequest)
            request = second
            report("OpenRouter Free rate-limited; trying Groq Free once for this website · Stop ■ to cancel.")
            second.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val message = if (e is java.net.SocketTimeoutException ||
                        e is java.io.InterruptedIOException)
                        "Groq Free website fallback timed out. No uncertain request was resent."
                    else "Groq Free website fallback connection failed. No paid fallback."
                    completeWebsite(call, serial, id, snapshot,
                        Result.failure(IllegalStateException(message)))
                }
                override fun onResponse(call: Call, response: Response) = completeWebsite(
                    call, serial, id, snapshot,
                    runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
            })
        }
    }

    private fun completeWebsite(call: Call, serial: Long, id: String,
                                snapshot: WorkspaceWebsiteGeneration.Snapshot,
                                result: Result<Map<String, String>>) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== call || !current(id)) return@runOnUiThread
            request = null
            result.onSuccess { generated ->
                runCatching {
                    WorkspaceWebsiteGeneration.apply(files, tasks, projects, snapshot, generated)
                }.onSuccess {
                    terminal(WorkspaceCodingResult.websiteSuccess(snapshot.original, generated))
                    activity.startActivity(WorkspacePreviewActivity.intent(activity, id))
                }.onFailure {
                    error("Website files were not fully saved: ${it.message}. " +
                        "If a backup is pending, use Review website · Undo in Chat.")
                }
            }.onFailure { error(it.message ?: "Free website provider failed; project files unchanged.") }
        }
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
                    terminal("Updated ${prepared.context.path} in your existing project. " +
                        "Review the file and use Undo / Keep in Chat. Preview/build is not verified.")
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
        if (projects.getProject(id)?.type == WorkspaceProjectType.WEBSITE) {
            val website = runCatching { WorkspaceWebsiteGeneration.pending(projects, id) }
                .getOrElse { error("Website backup unavailable: ${it.message}"); return }
            if (website != null) {
                AlertDialog.Builder(activity).setTitle("Review website build")
                    .setMessage("Files: index.html, style.css, script.js\n" +
                        "Your website request already authorized these writes. Keep is optional. " +
                        "Undo restores the previous files without overwriting newer manual edits. " +
                        "Check Work Preview; build success does not prove visual correctness.")
                    .setNegativeButton("Undo") { _, _ ->
                        runCatching { WorkspaceWebsiteGeneration.undo(files, projects, id) }
                            .onSuccess { report("Previous website files restored and locally verified.") }
                            .onFailure { error("Website Undo refused: ${it.message}") }
                    }.setNeutralButton("Later", null)
                    .setPositiveButton("Keep") { _, _ ->
                        runCatching { WorkspaceWebsiteGeneration.keep(files, projects, id) }
                            .onSuccess { report("Website build kept. Check Work Preview.") }
                            .onFailure { error("Website Keep refused: ${it.message}") }
                    }.show()
                return
            }
        }
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
