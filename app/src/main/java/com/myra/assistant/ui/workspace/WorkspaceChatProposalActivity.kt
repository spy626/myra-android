package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.ai.ApiKeyStore
import com.myra.assistant.ui.settings.ApiCloudSettingsActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.io.IOException

/** Chat instruction -> reviewed ONE file -> user-authorized free AI -> locally validated patch -> Safe Edit. */
class WorkspaceChatProposalActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_ID = "workspace_project_id"
        fun intent(context: Context, projectId: String): Intent =
            Intent(context, WorkspaceChatProposalActivity::class.java).putExtra(EXTRA_ID, projectId)
    }

    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private val files by lazy { WorkspaceFileStore(projects) }
    private val tasks by lazy { WorkspaceTaskStore(projects) }
    private val conversations by lazy {
        WorkspaceConversationStore(projects, File(noBackupFilesDir, "workspace-conversations"))
    }
    private val suggestions by lazy {
        WorkspaceAiSuggestionDraftStore(File(noBackupFilesDir, "workspace-ai-drafts"))
    }
    private val keys by lazy { ApiKeyStore(this) }
    private lateinit var projectId: String
    private lateinit var column: LinearLayout
    private var selectedPath: String? = null
    private var handoff: WorkspaceAiHandoff.Draft? = null
    private var reviewed: WorkspaceStructuredEdit.Draft? = null
    private var generation = 0L
    private var activeRequest: Call? = null
    private var message = "No source or instruction is sent until you approve a specific provider request."

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun label(text: String, size: Float = 14f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(220, 245, 224))
        setPadding(dp(15), dp(13), dp(15), dp(13))
        setTextIsSelectable(true)
    }
    private fun button(text: String, onClick: () -> Unit) {
        val view = label(text).apply {
            gravity = Gravity.CENTER
            setTextIsSelectable(false)
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        column.addView(view, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(9) })
    }
    private fun alert(error: Throwable) = Toast.makeText(this,
        error.message ?: "Workspace action refused", Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectId = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (projects.getProject(projectId) == null) {
            Toast.makeText(this, "Project unavailable", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(2, 6, 9))
            isFillViewport = true
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(18), dp(17), dp(40))
        }
        scroll.addView(column)
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::column.isInitialized) render()
    }

    override fun onStop() {
        generation++
        activeRequest?.cancel()
        activeRequest = null
        handoff = null
        reviewed = null
        super.onStop()
    }

    private fun latestInstruction(): String {
        val messages = conversations.read(projectId)
        val last = messages.lastOrNull { it.role == "user" }
            ?: throw IllegalArgumentException("Describe the change in this project's Chat first")
        return WorkspaceAiHandoff.normalizeFollowUp(last.text)
            .takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("The latest chat instruction is empty")
    }

    private fun render() {
        if (!::column.isInitialized) return
        column.removeAllViews()
        val project = projects.getProject(projectId)
        if (project == null) { finish(); return }
        button("←  Back to Chat / Work") { finish() }
        column.addView(label("${project.name} · AI change from Chat", 21f))
        column.addView(label(message, 12f))
        val pending = runCatching { WorkspaceScopedEdit.pending(projects, projectId) }
        if (pending.isFailure) {
            column.addView(label("Rollback data requires attention; no new request or write permitted."))
            return
        }
        if (pending.getOrNull() != null) {
            val backup = pending.getOrNull()!!
            column.addView(label("Protected edit pending: ${backup.path}. Review the website and then Undo or Keep."))
            if (project.type == WorkspaceProjectType.WEBSITE) button("Preview website") {
                startActivity(WorkspacePreviewActivity.intent(this, projectId))
            }
            button("Undo this protected edit") {
                AlertDialog.Builder(this).setTitle("Restore original file?")
                    .setMessage("Undo refuses to overwrite any newer work.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Undo") { _, _ ->
                        runCatching { WorkspaceScopedEdit.undo(files, projects, projectId) }
                            .onSuccess { message = "Original restored and verified."; render() }
                            .onFailure(::alert)
                    }.show()
            }
            button("Keep approved edit") {
                AlertDialog.Builder(this).setTitle("Keep this change?")
                    .setMessage("Release the protected rollback slot only if the edited file is unchanged?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Keep") { _, _ ->
                        runCatching { WorkspaceScopedEdit.keep(files, projects, projectId) }
                            .onSuccess { message = "Change kept. Return to Chat for the next request."; render() }
                            .onFailure(::alert)
                    }.show()
            }
            return
        }
        val task = tasks.get(projectId)
        if (task == null || !WorkspaceTaskContract.isSpecApproved(task) ||
            task.status == WorkspaceTaskStatus.PAUSED) {
            column.addView(label("Save and approve a project task brief before requesting source edits. Chat messages do not authorize writes."))
            button("Create / approve task brief") {
                startActivity(WorkspaceTaskActivity.intent(this, projectId))
            }
            return
        }
        val latest = runCatching { latestInstruction() }.getOrElse { error ->
            column.addView(label(error.message ?: "Chat instruction unavailable"))
            button("Return to Chat") { finish() }
            return
        }
        column.addView(label("Latest Chat request:\n$latest"))
        val recovered = runCatching {
            suggestions.recover(files, tasks, projects, projectId)
        }.getOrElse { error ->
            column.addView(label("Draft recovery unavailable: ${error.message.orEmpty()}"))
            return
        }
        if (recovered is WorkspaceAiSuggestionDraftStore.Recovery.Ready && reviewed == null) {
            reviewed = recovered.draft
            message = "Saved proposal revalidated against the current file and approved task."
        }
        if (reviewed != null) {
            val current = reviewed!!
            column.addView(label(current.displayText(), 12f))
            button("Review permission & Apply one file") {
                AlertDialog.Builder(this).setTitle("Apply exactly one reviewed edit?")
                    .setMessage("File: ${current.proposal.path}\nThis writes only the approved single-file patch. A protected rollback is saved first; no build or browser verification is claimed.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Apply via Safe Edit") { _, _ ->
                        runCatching {
                            WorkspaceScopedEdit.apply(files, tasks, projects, current.context, current.proposal)
                        }.onSuccess {
                            runCatching { suggestions.discard(projectId) }
                            reviewed = null
                            handoff = null
                            message = "One-file edit applied and verified against its expected SHA. Preview, then Undo or Keep."
                            render()
                        }.onFailure { error ->
                            message = "Apply refused: ${error.message.orEmpty()}. Rollback data, if created, is preserved."
                            render()
                        }
                    }.show()
            }
            button("Discard saved proposal") {
                AlertDialog.Builder(this).setTitle("Discard this proposal?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Discard") { _, _ ->
                        runCatching { suggestions.discard(projectId) }
                            .onSuccess { reviewed = null; handoff = null; message = "Proposal discarded; no source changed."; render() }
                            .onFailure(::alert)
                    }.show()
            }
            return
        }
        if (activeRequest != null) {
            column.addView(label("One request is in progress. No file write has been authorized."))
            return
        }
        val paths = runCatching { WorkspaceSourceContext.choices(files, projectId) }
            .getOrElse { column.addView(label("Files unavailable: ${it.message.orEmpty()}")); return }
        if (paths.isEmpty()) {
            column.addView(label("No eligible text files. Create a file with the existing editor first."))
            if (project.type == WorkspaceProjectType.WEBSITE) button("Create website starter (explicit approval)") {
                AlertDialog.Builder(this).setTitle("Create three starter files?")
                    .setMessage("Add index.html, style.css and script.js in this empty website project. Existing files will never be overwritten.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Create starter") { _, _ ->
                        runCatching { files.addWebsiteStarter(projectId) }
                            .onSuccess { message = "Starter created. Select a file for the latest Chat request."; render() }
                            .onFailure(::alert)
                    }.show()
            }
            button("Open existing editor") { startActivity(WorkspaceEditorActivity.intent(this, projectId)) }
            return
        }
        if (selectedPath !in paths) selectedPath = paths.firstOrNull()
        button("Selected file: ${selectedPath.orEmpty()} ▾") {
            AlertDialog.Builder(this).setTitle("Choose ONE project source file")
                .setItems(paths.toTypedArray()) { _, index ->
                    selectedPath = paths[index]
                    handoff = null
                    render()
                }.show()
        }
        button("Prepare & review local prompt") {
            runCatching {
                WorkspaceAiHandoff.prepare(files, tasks, projects, projectId,
                    requireNotNull(selectedPath), latestInstruction())
            }.onSuccess { prepared ->
                handoff = prepared
                message = "Local prompt prepared. Nothing has been sent or changed."
                render()
            }.onFailure(::alert)
        }
        val prepared = handoff
        if (prepared != null) {
            column.addView(label(prepared.displayText(), 12f))
            button("Review provider & send this one prompt") { confirmSend(prepared) }
        }
    }

    private fun confirmSend(prepared: WorkspaceAiHandoff.Draft) {
        val options = WorkspaceChatGateway.Provider.values().filter { provider ->
            runCatching { keys.get(if (provider == WorkspaceChatGateway.Provider.OPENROUTER_FREE)
                ApiKeyStore.OPENROUTER else ApiKeyStore.GEMINI).isNotBlank() }.getOrDefault(false)
        }
        if (options.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No configured Workspace provider")
                .setMessage("Save a Gemini or OpenRouter key in API & Cloud Settings first. No request has been made.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(this, ApiCloudSettingsActivity::class.java))
                }.show()
            return
        }
        AlertDialog.Builder(this).setTitle("Choose provider for this one-file proposal")
            .setItems(options.map { if (it == WorkspaceChatGateway.Provider.OPENROUTER_FREE)
                "OpenRouter $0 free router" else "Gemini 2.5 Flash · free-tier account only" }.toTypedArray()) { _, index ->
                val provider = options[index]
                AlertDialog.Builder(this).setTitle("Share reviewed source with provider?")
                    .setMessage("Provider: $provider\nProject: ${projects.getProject(projectId)?.name}\nFile: ${prepared.context.path}\n\n" +
                        "Your approved task, latest Chat instruction and up to 1,500 source characters will be shared. " +
                        "A privacy-pattern screen cannot detect all secrets. Verify the prompt above. " +
                        "Gemini free tier depends on YOUR account and billing setup; no automatic paid fallback, provider switch or file write. Proceed once?")
                    .setNegativeButton("Keep local", null)
                    .setPositiveButton("Send once") { _, _ -> send(prepared, provider) }
                    .show()
            }.show()
    }

    private fun send(prepared: WorkspaceAiHandoff.Draft, provider: WorkspaceChatGateway.Provider) {
        if (activeRequest != null || handoff !== prepared ||
            !WorkspaceAiHandoff.stillCurrent(files, tasks, projects, projectId, prepared)) {
            message = "Task or file changed. Reopen a fresh prompt; nothing was sent."
            handoff = null
            render()
            return
        }
        val key = runCatching { keys.get(if (provider == WorkspaceChatGateway.Provider.OPENROUTER_FREE)
            ApiKeyStore.OPENROUTER else ApiKeyStore.GEMINI) }.getOrElse { alert(it); return }
        val messages = listOf(WorkspaceConversationStore.Message("one-time-prompt", "user", prepared.prompt,
            System.currentTimeMillis()))
        val request = runCatching { WorkspaceChatGateway.request(provider, key, messages) }
            .getOrElse { alert(it); return }
        val serial = ++generation
        val call = WorkspaceChatGateway.client.newCall(request)
        activeRequest = call
        message = "Request in progress. No source write authorized."
        render()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                complete(call, serial, prepared, Result.failure(
                    IllegalStateException("Connection failed or request cancelled; no retry or fallback")))
            }
            override fun onResponse(call: Call, response: Response) {
                complete(call, serial, prepared, runCatching { WorkspaceChatGateway.read(provider, response) })
            }
        })
    }

    private fun complete(call: Call, serial: Long, prepared: WorkspaceAiHandoff.Draft,
                         result: Result<String>) {
        runOnUiThread {
            if (isFinishing || isDestroyed || serial != generation || activeRequest !== call ||
                handoff !== prepared) return@runOnUiThread
            activeRequest = null
            if (!WorkspaceAiHandoff.stillCurrent(files, tasks, projects, projectId, prepared)) {
                handoff = null
                message = "Source or task changed during request. Suggestion rejected; no edit made."
                render()
                return@runOnUiThread
            }
            result.onSuccess { response ->
                runCatching {
                    val draft = WorkspaceStructuredEdit.prepare(files, tasks, projects, projectId, response)
                    require(draft.context.path == prepared.context.path &&
                        draft.context.fileSha256 == prepared.context.fileSha256 &&
                        draft.context.specToken == prepared.context.specToken) {
                        "AI suggestion targeted another file or stale specification"
                    }
                    suggestions.save(files, tasks, projects, projectId, response, draft)
                    draft
                }.onSuccess {
                    reviewed = it
                    message = "AI proposal saved for offline review. No file changed."
                }.onFailure {
                    message = "Suggestion rejected by local safety/freshness checks: ${it.message.orEmpty()}. No file changed."
                }
            }.onFailure { message = it.message ?: "Provider request failed; no file changed." }
            render()
        }
    }
}
