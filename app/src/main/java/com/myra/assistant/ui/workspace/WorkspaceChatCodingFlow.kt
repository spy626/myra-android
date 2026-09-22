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
    // Counts actual provider calls, including retries; reset only at terminal/cancel.
    private var websiteAttempts = 0
    private var activeTurn: Pair<String, String>? = null
    val isRunning: Boolean get() = request != null

    fun cancel() {
        generation++
        request?.cancel()
        request = null
        websiteAttempts = 0
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
    private fun error(message: String) = terminal(WorkspaceCodingResult.failure(message), "")
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
        val cloudApproved = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            .getBoolean(WorkspaceCloudflareFree.PREFERENCE_KEY, false)
        val cloudKey = if (cloudApproved) keys.get(ApiKeyStore.CLOUDFLARE_TOKEN) else ""
        val cloudAccount = if (cloudApproved) keys.get(ApiKeyStore.CLOUDFLARE_ACCOUNT) else ""
        if (cloudApproved && !WorkspaceCloudflareFree.configured(true, cloudKey, cloudAccount)) {
            error("Cloudflare Workers Free needs a valid token and Account ID in API Settings. No source sent.")
            return
        }
        val xKiroEnabled = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            .getBoolean(WorkspaceXKiroFree.PREFERENCE_KEY, false)
        val xKiroKey = if (xKiroEnabled) runCatching { keys.get(ApiKeyStore.XKIRO) }
            .getOrElse { error("Secure xKiro key unavailable; nothing was shared."); return } else ""
        val usingXKiro = !cloudApproved && xKiroEnabled && WorkspaceXKiroFree.validKey(xKiroKey)
        val key = if (cloudApproved) cloudKey else if (usingXKiro) xKiroKey
            else runCatching { keys.get(ApiKeyStore.OPENROUTER) }
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
        prepareSource(id, instruction, key, usingXKiro, cloudApproved, cloudAccount)
    }

    /** Explicit 'build website' Send authorizes generation of the three named project files.
     * The AI proposes text; the local file owner checks snapshots and saves a durable Undo.
     * No additional per-file permission popup is needed for this requested website build.
     */
    private fun continueWebsite(id: String, instruction: String) {
        // Validate the full website brief before changing a pending backup or task.
        // Ordinary Android single-file edits retain their separate 500-character contract.
        val fullBrief = runCatching {
            WorkspaceTaskContract.normalizeGoal(instruction, WorkspaceProjectType.WEBSITE)
        }.getOrElse {
            error("Website brief could not be accepted: ${it.message}")
            return
        }
        val openRouterKey = runCatching { keys.get(ApiKeyStore.OPENROUTER) }
            .getOrElse { error("Secure provider keys unavailable; no source was shared."); return }
        val groqKey = runCatching { keys.get(ApiKeyStore.GROQ) }
            .getOrElse { error("Secure provider keys unavailable; no source was shared."); return }
        val cloudApproved = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            .getBoolean(WorkspaceCloudflareFree.PREFERENCE_KEY, false)
        val cloudKey = if (cloudApproved) keys.get(ApiKeyStore.CLOUDFLARE_TOKEN) else ""
        val cloudAccount = if (cloudApproved) keys.get(ApiKeyStore.CLOUDFLARE_ACCOUNT) else ""
        if (cloudApproved && !WorkspaceCloudflareFree.configured(true, cloudKey, cloudAccount)) {
            error("Cloudflare Workers Free needs a valid token and Account ID in API Settings. No source sent.")
            return
        }
        val groqFreeEnabled = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            .getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
        val xKiroApproved = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            .getBoolean(WorkspaceXKiroFree.PREFERENCE_KEY, false)
        val xKiroKey = if (xKiroApproved) runCatching { keys.get(ApiKeyStore.XKIRO) }
            .getOrElse { error("Secure xKiro key unavailable; no source was shared."); return } else ""
        val primary = if (cloudApproved) WorkspaceWebsiteRoute.Provider.CLOUDFLARE
        else if (xKiroApproved && WorkspaceXKiroFree.validKey(xKiroKey))
            WorkspaceWebsiteRoute.Provider.XKIRO
        else WorkspaceWebsiteRoute.choose(openRouterKey, groqKey, groqFreeEnabled)
        if (primary == null) {
            error("Website request saved locally. Save a valid OpenRouter Free key, or enable " +
                "Groq Free/ZDR with a valid Groq Free key in API & Cloud Settings. No source was sent.")
            return
        }
        runCatching { WorkspaceWebsiteGeneration.finishPreviousForNewRequest(files, projects, id) }
            .onFailure { error("Previous edit needs attention: ${it.message}. Use Undo in Chat."); return }
        runCatching {
            val previous = tasks.get(id)
            val criteria = previous?.acceptanceCriteria?.takeIf { it.isNotBlank() }
                ?: "The three saved website files are inspectable in Work Preview; preserve existing work."
            val saved = tasks.create(id, fullBrief, replaceExisting = previous != null,
                rawAcceptanceCriteria = criteria)
            tasks.setSpecificationApproved(id, saved.taskId, WorkspaceTaskContract.specToken(saved), true)
        }.onFailure { error("Website task could not be saved: ${it.message}"); return }
        val snapshot = runCatching { WorkspaceWebsiteGeneration.prepare(files, tasks, projects, id) }
            .getOrElse { error("Website source review blocked: ${it.message}"); return }
        val outgoing = runCatching {
            when (primary) {
                WorkspaceWebsiteRoute.Provider.OPENROUTER ->
                    WorkspaceWebsiteGeneration.request(openRouterKey, snapshot)
                WorkspaceWebsiteRoute.Provider.GROQ ->
                    WorkspaceWebsiteGroqFallback.request(groqKey, snapshot)
                WorkspaceWebsiteRoute.Provider.XKIRO ->
                    WorkspaceXKiroFree.websiteRequest(xKiroKey, snapshot)
                WorkspaceWebsiteRoute.Provider.CLOUDFLARE ->
                    WorkspaceCloudflareFree.websiteRequest(cloudKey, cloudAccount, snapshot,
                        WorkspaceCloudflareFree.chosenModel(activity.getSharedPreferences(
                            "workspace_ui", Context.MODE_PRIVATE).getString(
                            WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY, WorkspaceCloudflareFree.MODEL)))
            }
        }.getOrElse { error("Free website request refused: ${it.message}"); return }
        val serial = ++generation
        val client = when (primary) {
            WorkspaceWebsiteRoute.Provider.GROQ -> WorkspaceWebsiteGroqFallback.client
            WorkspaceWebsiteRoute.Provider.XKIRO -> WorkspaceXKiroFree.client
            WorkspaceWebsiteRoute.Provider.OPENROUTER -> WorkspaceWebsiteGeneration.client
            WorkspaceWebsiteRoute.Provider.CLOUDFLARE -> WorkspaceCloudflareFree.client
        }
        val call = client.newCall(outgoing)
        websiteAttempts = 1
        request = call
        report(if (primary == WorkspaceWebsiteRoute.Provider.CLOUDFLARE)
            "Building website · Cloudflare Workers AI Free · Stop ■ to cancel."
        else if (primary == WorkspaceWebsiteRoute.Provider.XKIRO)
            "Building website · xKiro Free attempt 1/1 · Stop ■ to cancel."
        else "Building website · free attempt 1/3 · Stop ■ to cancel.")
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val message = if (primary == WorkspaceWebsiteRoute.Provider.XKIRO &&
                    e.message?.startsWith("xKiro Free") == true) e.message!!
                else if (primary == WorkspaceWebsiteRoute.Provider.CLOUDFLARE &&
                    (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException))
                    "Cloudflare website stream timed out or stalled; server outcome is uncertain. " +
                        "No files changed or automatic retry. Check your daily neurons before manually trying another Free model."
                else if (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException)
                    "Website provider timed out. No incomplete code was saved."
                else "Website provider connection failed; no result received. No paid fallback."
                completeWebsite(call, serial, id, snapshot,
                    Result.failure(IllegalStateException(message)))
            }
            override fun onResponse(call: Call, response: Response) {
                val rejectedStatus = response.code
                if (WorkspaceWebsiteGroqFallback.compatibilityEligible(primary, rejectedStatus)) {
                    response.close() // Definitive HTTP rejection, not an uncertain timeout.
                    recoverGroqWebsiteFormat(call, serial, id, snapshot, groqKey)
                    return
                }
                if (primary == WorkspaceWebsiteRoute.Provider.OPENROUTER &&
                    WorkspaceWebsiteGroqFallback.routeRejected(rejectedStatus)) {
                    // The user enabled website source sharing once in Settings; no per-edit
                    // permission dialog. Only a definitive HTTP rejection may switch routes.
                    response.close()
                    fallbackWebsiteOnRejected(call, serial, id, snapshot, rejectedStatus)
                    return
                }
                val via = if (primary == WorkspaceWebsiteRoute.Provider.CLOUDFLARE)
                    "Cloudflare Workers AI Free"
                else if (primary == WorkspaceWebsiteRoute.Provider.XKIRO)
                    WorkspaceCodingAutoFallback.displayName(response.request.url.toString()) else null
                completeWebsite(call, serial, id, snapshot,
                    runCatching { when (primary) {
                        WorkspaceWebsiteRoute.Provider.XKIRO -> WorkspaceXKiroFree.readWebsite(response)
                        WorkspaceWebsiteRoute.Provider.CLOUDFLARE -> WorkspaceCloudflareFree.readWebsite(response)
                        else -> WorkspaceWebsiteGeneration.readResponse(response)
                    } }, via)
            }
        })
    }

    /** One same-provider JSON Object Mode compatibility attempt after a definitive
     * Groq schema HTTP 400, whether Groq was primary or the consented fallback.
     * It is terminal: never replay a timeout, invalid answer, or this third response.
     */
    private fun recoverGroqWebsiteFormat(first: Call, serial: Long, id: String,
                                         snapshot: WorkspaceWebsiteGeneration.Snapshot,
                                         groqKey: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== first || !current(id)) return@runOnUiThread
            if (!WorkspaceWebsiteGroqFallback.canAttempt(websiteAttempts)) {
                completeWebsite(first, serial, id, snapshot, Result.failure(
                    IllegalStateException("All eligible free website attempts failed; no files changed.")))
                return@runOnUiThread
            }
            val alternate = runCatching {
                WorkspaceWebsiteGroqFallback.compatibilityRequest(groqKey, snapshot)
            }.getOrElse {
                completeWebsite(first, serial, id, snapshot, Result.failure(
                    IllegalStateException("Groq Free rejected this website format; no files changed.")))
                return@runOnUiThread
            }
            val second = WorkspaceWebsiteGroqFallback.client.newCall(alternate)
            websiteAttempts++
            request = second
            report("Trying compatible Groq Free format · attempt $websiteAttempts/3 · Stop ■ to cancel.")
            second.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    completeWebsite(call, serial, id, snapshot, Result.failure(
                        IllegalStateException("Groq Free compatibility attempt could not complete. " +
                            "No uncertain request was resent; project files unchanged.")))
                }
                override fun onResponse(call: Call, response: Response) = completeWebsite(
                    call, serial, id, snapshot,
                    runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
            })
        }
    }

    private fun fallbackWebsiteOnRejected(first: Call, serial: Long, id: String,
                                           snapshot: WorkspaceWebsiteGeneration.Snapshot,
                                           statusCode: Int) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== first || !current(id)) return@runOnUiThread
            if (!WorkspaceWebsiteGroqFallback.canAttempt(websiteAttempts)) {
                completeWebsite(first, serial, id, snapshot, Result.failure(
                    IllegalStateException("All eligible free website attempts failed; no files changed.")))
                return@runOnUiThread
            }
            val preferences = activity.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            val optedIn = preferences.getBoolean(WorkspaceWebsiteGroqFallback.PREFERENCE_KEY, false)
            val groqFree = preferences.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false)
            val key = runCatching { keys.get(ApiKeyStore.GROQ) }.getOrDefault("")
            if (!WorkspaceWebsiteGroqFallback.eligible(statusCode, optedIn, groqFree, key)) {
                completeWebsite(first, serial, id, snapshot, Result.failure(
                    IllegalStateException("Primary free route HTTP $statusCode. Website Groq fallback is unavailable or OFF. " +
                        "To enable a one-time automatic switch, save a Groq Free key and enable " +
                        "Groq Free/ZDR plus the separate website-source fallback setting. No paid fallback.")))
                return@runOnUiThread
            }
            val secondRequest = runCatching { WorkspaceWebsiteGroqFallback.request(key, snapshot) }
                .getOrElse { issue ->
                    completeWebsite(first, serial, id, snapshot, Result.failure(
                        IllegalStateException("Primary free route HTTP $statusCode; Groq Free website fallback not sent: " +
                            "${issue.message}. No files changed.")))
                    return@runOnUiThread
                }
            val second = WorkspaceWebsiteGroqFallback.client.newCall(secondRequest)
            websiteAttempts++
            request = second
            report("Switching to Groq Free · attempt $websiteAttempts/3 · Stop ■ to cancel.")
            second.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val message = if (e is java.net.SocketTimeoutException ||
                        e is java.io.InterruptedIOException)
                        "Groq Free website fallback timed out. No uncertain request was resent."
                    else "Groq Free website fallback connection failed. No paid fallback."
                    completeWebsite(call, serial, id, snapshot,
                        Result.failure(IllegalStateException(message)))
                }
                override fun onResponse(call: Call, response: Response) {
                    // Phone failure: Groq was the SECOND provider, so the primary-Groq
                    // branch never ran. One definite 400 permits the THIRD request,
                    // with the exact same approved snapshot in JSON Object Mode.
                    if (WorkspaceWebsiteGroqFallback.recoverAfterFallbackGroq(
                            response.code, websiteAttempts)) {
                        response.close()
                        recoverGroqWebsiteFormat(call, serial, id, snapshot, key)
                        return
                    }
                    completeWebsite(call, serial, id, snapshot,
                        runCatching { WorkspaceWebsiteGeneration.readResponse(response) })
                }
            })
        }
    }

    private fun completeWebsite(call: Call, serial: Long, id: String,
                                snapshot: WorkspaceWebsiteGeneration.Snapshot,
                                result: Result<Map<String, String>>,
                                via: String? = null) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || serial != generation ||
                request !== call || !current(id)) return@runOnUiThread
            request = null
            websiteAttempts = 0
            result.onSuccess { generated ->
                val review = runCatching { WorkspaceWebsiteVisualQuality.review(snapshot, generated) }
                    .getOrElse { issue ->
                        error("Website layout safeguard rejected this output: ${issue.message}. No files changed.")
                        return@runOnUiThread
                    }
                runCatching {
                    WorkspaceWebsiteGeneration.apply(files, tasks, projects, snapshot, review.files)
                }.onSuccess {
                    val summary = WorkspaceCodingResult.websiteSuccess(snapshot.original, review.files) +
                        review.chatNote() + (when (via) {
                            "Cloudflare Workers AI Free" -> " Completed via Cloudflare Workers AI Free."
                            null -> ""
                            else -> " Completed via $via after xKiro was unavailable."
                        })
                    terminal(summary, "") // The durable Chat reply is the single success message.
                    activity.startActivity(WorkspacePreviewActivity.intent(activity, id))
                }.onFailure {
                    error("Website files were not fully saved: ${it.message}. " +
                        "If a backup is pending, use Review website · Undo in Chat.")
                }
            }.onFailure { error(it.message ?: "Free website provider failed; project files unchanged.") }
        }
    }

    private fun prepareSource(id: String, instruction: String, key: String,
                              usingXKiro: Boolean, usingCloudflare: Boolean, cloudAccount: String) {
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
        send(id, key, prepared, usingXKiro, usingCloudflare, cloudAccount)
    }

    private fun send(id: String, key: String, prepared: WorkspaceAiHandoff.Draft,
                     usingXKiro: Boolean, usingCloudflare: Boolean, cloudAccount: String) {
        if (isRunning || !current(id) || !WorkspaceAiHandoff.stillCurrent(files, tasks, projects, id, prepared)) {
            error("Project or source changed; request stopped. No source was sent.")
            return
        }
        val provider = WorkspaceChatGateway.Provider.OPENROUTER_FREE
        val messages = listOf(WorkspaceConversationStore.Message("explicit-one-file-prompt", "user",
            prepared.prompt, System.currentTimeMillis()))
        val outgoing = runCatching { if (usingCloudflare)
            WorkspaceCloudflareFree.editRequest(key, cloudAccount, prepared.prompt,
                WorkspaceCloudflareFree.chosenModel(activity.getSharedPreferences(
                    "workspace_ui", Context.MODE_PRIVATE).getString(
                    WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY, WorkspaceCloudflareFree.MODEL)))
        else if (usingXKiro) WorkspaceXKiroFree.editRequest(key, prepared.prompt)
        else WorkspaceChatGateway.request(provider, key, messages) }
            .getOrElse { error("Provider request refused: ${it.message}"); return }
        val serial = ++generation
        val call = (if (usingCloudflare) WorkspaceCloudflareFree.client
            else if (usingXKiro) WorkspaceXKiroFree.client
            else WorkspaceChatGateway.client).newCall(outgoing)
        request = call
        report("Working on ${prepared.context.path} · Stop ■ to cancel. One-file Safe Edit only.")
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = complete(call, serial, id, prepared,
                Result.failure(IllegalStateException(if (usingXKiro &&
                    e.message?.startsWith("xKiro Free") == true) e.message!!
                else WorkspaceFreeAiSuggestion.networkFailure(e))))
            override fun onResponse(call: Call, response: Response) = complete(call, serial, id,
                prepared, runCatching { if (usingCloudflare) WorkspaceCloudflareFree.readEdit(response)
                    else if (usingXKiro) WorkspaceXKiroFree.readEdit(response)
                    else WorkspaceChatGateway.read(provider, response) },
                if (usingCloudflare) "Cloudflare Workers AI Free"
                else if (usingXKiro) WorkspaceCodingAutoFallback.displayName(response.request.url.toString()) else null)
        })
    }

    private fun complete(call: Call, serial: Long, id: String, prepared: WorkspaceAiHandoff.Draft,
                         result: Result<String>, via: String? = null) {
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
                        "Review the file and use Undo / Keep in Chat. Preview/build is not verified." +
                        (when (via) {
                            "Cloudflare Workers AI Free" -> " Completed via Cloudflare Workers AI Free."
                            null -> ""
                            else -> " Completed via $via after xKiro was unavailable."
                        }))
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
