package com.myra.assistant.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.io.IOException

/** One Workspace editing boundary: manual JSON or an opt-in free AI suggestion; only Safe Edit writes. */
class WorkspaceStructuredEditActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PROJECT_ID = "workspace_project_id"
        fun intent(context: Context, projectId: String): Intent =
            Intent(context, WorkspaceStructuredEditActivity::class.java)
                .putExtra(EXTRA_PROJECT_ID, projectId)
    }

    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private val tasks by lazy { WorkspaceTaskStore(projects) }
    private val files by lazy { WorkspaceFileStore(projects) }
    private val savedSuggestions by lazy {
        WorkspaceAiSuggestionDraftStore(File(noBackupFilesDir, "workspace-ai-drafts"))
    }
    private lateinit var projectId: String
    private lateinit var status: TextView
    private lateinit var handoffButton: TextView
    private lateinit var handoffPreview: TextView
    private lateinit var copyPromptButton: TextView
    private lateinit var freeKeyInput: EditText
    private lateinit var freeSuggestionButton: TextView
    private lateinit var restoreButton: TextView
    private lateinit var discardButton: TextView
    private lateinit var jsonInput: EditText
    private lateinit var preview: TextView
    private lateinit var validateButton: TextView
    private lateinit var applyButton: TextView
    private lateinit var undoButton: TextView
    private lateinit var keepButton: TextView
    private var handoff: WorkspaceAiHandoff.Draft? = null
    private var draft: WorkspaceStructuredEdit.Draft? = null
    private var activeRequest: Call? = null
    private var requestGeneration = 0L

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun button(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(212, 248, 217))
        textSize = 14f
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_workspace_dialog_input)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(10)
        }
    }
    private fun panel() = TextView(this).apply {
        setTextColor(Color.rgb(217, 243, 222))
        textSize = 12f
        setTextIsSelectable(true)
        setBackgroundResource(R.drawable.bg_workspace_project_card)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(217, 243, 222))
        textSize = 13f
        setPadding(dp(4), dp(10), dp(4), dp(6))
    }
    private fun showDialog(builder: AlertDialog.Builder) {
        val dialog = builder.create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_workspace_dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.rgb(190, 255, 202))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.rgb(190, 255, 202))
    }
    private fun error(t: Throwable) =
        Toast.makeText(this, t.message ?: "Structured edit refused", Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        val project = projects.getProject(projectId)
        if (project == null) {
            Toast.makeText(this, "Workspace project unavailable", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val root = ScrollView(this).apply { setBackgroundColor(Color.rgb(2, 6, 9)) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(48))
        }
        root.addView(column)
        setContentView(root)

        column.addView(button("←  Back to project").apply { setOnClickListener { finish() } })
        column.addView(label("AI PATCH HANDOFF  •  ${project.name}").apply {
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        column.addView(label(
            "Choose one file and inspect a bounded source prompt. Copy it manually OR explicitly request " +
                "one free AI suggestion. No automatic send, paid fallback, source write or completion claim. " +
                "Every AI response remains an untrusted patch with separate write approval."
        ))
        status = panel().also(column::addView)
        handoffButton = button("Prepare one-file AI prompt (local)").also(column::addView)
        handoffPreview = panel().also(column::addView)
        copyPromptButton = button("Review privacy & copy prompt").also(column::addView)
        column.addView(label(
            "OPTIONAL NATIVE AI: OpenRouter's published $0 free-model router only. " +
                "Enter a key from a free account with no billing/card. Key stays in this screen's memory " +
                "for one request, is never saved and is cleared when leaving. Free quota and privacy-compatible " +
                "providers may be unavailable: LYRA stops instead of switching to paid or relaxing privacy. " +
                "Do not use confidential source; privacy-pattern screening cannot catch everything. " +
                "A locally validated suggestion can be kept for up to 24 hours in app-private no-backup storage; " +
                "the patch itself may contain source text. Discard it when finished."
        ))
        freeKeyInput = EditText(this).apply {
            hint = "OpenRouter FREE account API key (session-only)"
            setTextColor(Color.rgb(241, 255, 243))
            setHintTextColor(Color.rgb(140, 167, 149))
            textSize = 14f
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(256))
            isSaveEnabled = false // Android must not put a session-only key in saved instance state.
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(freeKeyInput)
        freeSuggestionButton = button("Review & request ONE free AI suggestion").also(column::addView)
        restoreButton = button("Restore saved AI patch (offline review)").also(column::addView)
        discardButton = button("Discard saved AI patch").also(column::addView)
        column.addView(label(
            "Paste exactly ONE JSON response from an AI or type it yourself. No provider reply is " +
                "trusted until LYRA rechecks the current file, source SHA, task and privacy.\n\n" +
                "JSON format:\n" +
                "{\"schemaVersion\":1,\"operation\":\"replace_exact_once\",\"path\":\"index.html\"," +
                "\"oldText\":\"Hello\",\"newText\":\"Welcome\",\"rationale\":\"Why this matches the saved spec\"}"
        ))
        jsonInput = EditText(this).apply {
            hint = "Paste a single structured edit JSON object"
            setTextColor(Color.rgb(241, 255, 243))
            setHintTextColor(Color.rgb(140, 167, 149))
            textSize = 13f
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6000))
            minLines = 7
            maxLines = 14
            isSaveEnabled = false // Recovered drafts must be revalidated, not restored by Android.
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(jsonInput)
        validateButton = button("Validate current scope & preview patch").also(column::addView)
        preview = panel().also(column::addView)
        applyButton = button("Review permission & apply via Safe Edit").also(column::addView)
        undoButton = button("Undo protected edit").also(column::addView)
        keepButton = button("Keep change & clear rollback").also(column::addView)

        handoffButton.setOnClickListener { selectHandoffFile() }
        copyPromptButton.setOnClickListener { confirmCopyPrompt() }
        freeSuggestionButton.setOnClickListener { confirmFreeSuggestion() }
        restoreButton.setOnClickListener { restoreSuggestion() }
        discardButton.setOnClickListener { confirmDiscardSuggestion() }
        jsonInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = clearDraft()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        validateButton.setOnClickListener { validateDraft() }
        applyButton.setOnClickListener { confirmApply() }
        undoButton.setOnClickListener {
            showDialog(AlertDialog.Builder(this)
                .setTitle("Restore saved original?")
                .setMessage("Undo only if the file still exactly matches this protected edit. Later work will never be overwritten.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Undo") { _, _ ->
                    runCatching { WorkspaceScopedEdit.undo(files, projects, projectId) }
                        .onSuccess {
                            runCatching { savedSuggestions.discard(projectId) }
                            Toast.makeText(this, "Original restored and checked", Toast.LENGTH_LONG).show()
                            render()
                        }.onFailure(::error)
                })
        }
        keepButton.setOnClickListener {
            showDialog(AlertDialog.Builder(this)
                .setTitle("Keep edited file?")
                .setMessage("This releases the private rollback slot only if the current file still matches the approved edit.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Keep change") { _, _ ->
                    runCatching { WorkspaceScopedEdit.keep(files, projects, projectId) }
                        .onSuccess {
                            runCatching { savedSuggestions.discard(projectId) }
                            Toast.makeText(this, "Change kept; rollback cleared", Toast.LENGTH_LONG).show()
                            render()
                        }.onFailure {
                            render()
                            error(it)
                        }
                })
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) render()
    }

    override fun onStop() {
        // No background AI processing or persistent key. Late callbacks cannot update a new screen.
        requestGeneration++
        activeRequest?.cancel()
        activeRequest = null
        if (::freeKeyInput.isInitialized) freeKeyInput.text?.clear()
        if (::jsonInput.isInitialized) jsonInput.text?.clear()
        if (::handoffPreview.isInitialized) clearHandoff()
        if (::preview.isInitialized) clearDraft()
        super.onStop()
    }

    private fun clearDraft() {
        draft = null
        preview.text = ""
        preview.visibility = View.GONE
        applyButton.visibility = View.GONE
    }
    private fun clearHandoff() {
        handoff = null
        handoffPreview.text = ""
        handoffPreview.visibility = View.GONE
        copyPromptButton.visibility = View.GONE
    }

    private fun render() {
        clearDraft()
        clearHandoff() // Source-bearing prompt never survives leaving and returning.
        restoreButton.visibility = View.GONE
        discardButton.visibility = View.GONE
        val pending = runCatching { WorkspaceScopedEdit.pending(projects, projectId) }
        if (pending.isFailure) {
            status.text = "Rollback data needs attention. No new write or prompt will be attempted."
            handoffButton.visibility = View.GONE
            freeKeyInput.visibility = View.GONE
            freeSuggestionButton.visibility = View.GONE
            jsonInput.visibility = View.GONE
            validateButton.visibility = View.GONE
            undoButton.visibility = View.GONE
            keepButton.visibility = View.GONE
            return
        }
        val backup = pending.getOrNull()
        if (backup != null) {
            // A protected edit takes precedence over any older suggestion.
            runCatching { savedSuggestions.discard(projectId) }
            status.text = "PENDING PROTECTED EDIT\nFile: ${backup.path}\n" +
                "Undo or Keep before another patch. No build/browser verification claimed."
            handoffButton.visibility = View.GONE
            freeKeyInput.visibility = View.GONE
            freeSuggestionButton.visibility = View.GONE
            jsonInput.visibility = View.GONE
            validateButton.visibility = View.GONE
            undoButton.visibility = View.VISIBLE
            keepButton.visibility = View.VISIBLE
            return
        }
        undoButton.visibility = View.GONE
        keepButton.visibility = View.GONE
        val task = tasks.get(projectId)
        val ready = task != null && WorkspaceTaskContract.isSpecApproved(task) &&
            task.status != WorkspaceTaskStatus.PAUSED
        val recovered = runCatching { savedSuggestions.recover(files, tasks, projects, projectId) }
        val hasSaved = recovered.getOrNull() is WorkspaceAiSuggestionDraftStore.Recovery.Ready
        status.text = when {
            recovered.isFailure -> "Private draft storage needs attention; no saved suggestion opened."
            hasSaved -> "Saved AI patch available for offline review. Restore or discard it before another request. " +
                "No source has been changed."
            recovered.getOrNull() == WorkspaceAiSuggestionDraftStore.Recovery.Rejected ->
                "Saved AI patch expired or failed current-file/task checks and was discarded. No edit made."
            ready -> "Choose a file → review a local AI prompt → opt in to one $0 suggestion OR copy manually. " +
                "Then preview and separately approve a safe edit."
            else -> "Save, approve and resume the task brief first. No source sharing, AI request or write is authorized."
        }
        handoffButton.visibility = if (ready && !hasSaved) View.VISIBLE else View.GONE
        freeKeyInput.visibility = if (ready && !hasSaved) View.VISIBLE else View.GONE
        freeSuggestionButton.visibility = if (ready && !hasSaved) View.VISIBLE else View.GONE
        restoreButton.visibility = if (hasSaved) View.VISIBLE else View.GONE
        discardButton.visibility = if (hasSaved) View.VISIBLE else View.GONE
        jsonInput.visibility = if (ready) View.VISIBLE else View.GONE
        validateButton.visibility = if (ready) View.VISIBLE else View.GONE
    }

    private fun restoreSuggestion() {
        if (activeRequest != null) return
        when (val saved = runCatching {
            savedSuggestions.recover(files, tasks, projects, projectId)
        }.getOrElse { error(it); return }) {
            is WorkspaceAiSuggestionDraftStore.Recovery.Ready -> {
                jsonInput.setText(saved.reply)
                draft = saved.draft
                preview.text = saved.draft.displayText()
                preview.visibility = View.VISIBLE
                applyButton.visibility = View.VISIBLE
                status.text = "Saved AI patch restored and freshly validated. UNTRUSTED preview only; separate write approval required."
            }
            WorkspaceAiSuggestionDraftStore.Recovery.Missing -> render()
            WorkspaceAiSuggestionDraftStore.Recovery.Rejected -> {
                render()
                status.text = "Saved AI patch expired or failed current-file/task checks; discarded. No edit made."
            }
        }
    }

    private fun confirmDiscardSuggestion() {
        showDialog(AlertDialog.Builder(this)
            .setTitle("Discard saved AI patch?")
            .setMessage("Remove this project's temporary suggestion? No file will be changed and no provider contacted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Discard patch") { _, _ ->
                runCatching { savedSuggestions.discard(projectId) }
                    .onSuccess {
                        jsonInput.text?.clear()
                        render()
                        status.text = "Saved AI patch discarded. No file changed or provider contacted."
                    }.onFailure(::error)
            })
    }

    private fun selectHandoffFile() {
        if (activeRequest != null) {
            Toast.makeText(this, "Finish this free suggestion request first", Toast.LENGTH_SHORT).show()
            return
        }
        if (savedSuggestions.recover(files, tasks, projects, projectId) is WorkspaceAiSuggestionDraftStore.Recovery.Ready) {
            status.text = "Restore or discard the saved AI patch before preparing another request."
            return
        }
        val choices = runCatching { WorkspaceSourceContext.choices(files, projectId) }
            .getOrElse { error(it); return }
        if (choices.isEmpty()) {
            Toast.makeText(this, "No eligible project text files to share", Toast.LENGTH_LONG).show()
            return
        }
        showDialog(AlertDialog.Builder(this)
            .setTitle("Choose ONE file for a local prompt")
            .setItems(choices.toTypedArray()) { _, which ->
                runCatching { WorkspaceAiHandoff.prepare(files, tasks, projects, projectId, choices[which]) }
                    .onSuccess {
                        handoff = it
                        handoffPreview.text = it.displayText()
                        handoffPreview.visibility = View.VISIBLE
                        copyPromptButton.visibility = View.VISIBLE
                    }.onFailure {
                        clearHandoff()
                        error(it)
                    }
            }
            .setNegativeButton("Cancel", null))
    }

    private fun confirmCopyPrompt() {
        val prepared = handoff ?: return
        if (!WorkspaceAiHandoff.stillCurrent(files, tasks, projects, projectId, prepared)) {
            clearHandoff()
            Toast.makeText(this, "Source or task changed; prepare a fresh prompt", Toast.LENGTH_LONG).show()
            return
        }
        showDialog(AlertDialog.Builder(this)
            .setTitle("Copy this source to Android clipboard?")
            .setMessage(
                "Selected file: ${prepared.context.path}\n" +
                    "The prompt includes its ${if (prepared.context.truncated) "first 1500 source characters" else "complete source"}, " +
                    "your goal and acceptance criteria. Other apps may read your clipboard; privacy patterns cannot catch every secret. " +
                    "Choose where to paste it and clear the clipboard afterward. No provider is contacted by Copy."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Copy this prompt") { _, _ ->
                if (!WorkspaceAiHandoff.stillCurrent(files, tasks, projects, projectId, prepared)) {
                    clearHandoff()
                    Toast.makeText(this, "Source or task changed; nothing copied", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("LYRA one-file AI prompt", prepared.prompt))
                Toast.makeText(this, "Prompt copied by choice; paste only in an AI you trust", Toast.LENGTH_LONG).show()
            })
    }

    private fun confirmFreeSuggestion() {
        if (activeRequest != null) {
            Toast.makeText(this, "Free AI request already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        val savedState = runCatching { savedSuggestions.recover(files, tasks, projects, projectId) }
            .getOrElse { error(it); return }
        if (savedState is WorkspaceAiSuggestionDraftStore.Recovery.Ready) {
            status.text = "Restore or discard the saved AI patch first; no new request sent."
            return
        }
        val prepared = handoff ?: run {
            Toast.makeText(this, "Prepare and review one file's local prompt first", Toast.LENGTH_LONG).show()
            return
        }
        if (!WorkspaceAiHandoff.stillCurrent(files, tasks, projects, projectId, prepared)) {
            clearHandoff()
            Toast.makeText(this, "Source or task changed; prepare a fresh prompt", Toast.LENGTH_LONG).show()
            return
        }
        val key = freeKeyInput.text.toString().trim()
        val request = runCatching { WorkspaceFreeAiSuggestion.request(key, prepared.prompt) }
            .getOrElse { error(it); return }
        showDialog(AlertDialog.Builder(this)
            .setTitle("SEND one source prompt to FREE AI?")
            .setMessage(
                "Send selected ${prepared.context.path} (${if (prepared.context.truncated) "first 1500 characters" else "entire selected source"}), " +
                    "the saved goal and acceptance criteria to OpenRouter's $0 openrouter/free route? " +
                    "Other services will process the text. LYRA requires provider zero retention and denies " +
                    "provider data collection; if no matching free endpoint exists, the call FAILS. " +
                    "Only use a no-billing free account and non-confidential source. " +
                    "One request, no automatic retry, no paid fallback and NO file write."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send THIS one free request") { _, _ ->
                if (!WorkspaceAiHandoff.stillCurrent(files, tasks, projects, projectId, prepared)) {
                    clearHandoff()
                    Toast.makeText(this, "Source or task changed; nothing sent", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (activeRequest != null) return@setPositiveButton
                if (savedSuggestions.recover(files, tasks, projects, projectId) is WorkspaceAiSuggestionDraftStore.Recovery.Ready) {
                    status.text = "Saved AI patch exists; no new request sent."
                    return@setPositiveButton
                }
                freeKeyInput.text?.clear()
                clearDraft()
                val generation = ++requestGeneration
                val call = WorkspaceFreeAiSuggestion.client.newCall(request)
                activeRequest = call
                status.text = "One free suggestion requested. No file will be changed without separate approval."
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            if (generation != requestGeneration || isFinishing || isDestroyed) return@runOnUiThread
                            activeRequest = null
                            status.text = "Free AI network unavailable. No edit made and no paid retry attempted."
                        }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { WorkspaceFreeAiSuggestion.readResponse(response) }
                        runOnUiThread {
                            if (generation != requestGeneration || isFinishing || isDestroyed) return@runOnUiThread
                            activeRequest = null
                            if (!WorkspaceAiHandoff.stillCurrent(files, tasks, projects, projectId, prepared)) {
                                clearHandoff()
                                clearDraft()
                                status.text = "Source, task or approval changed; AI reply discarded. No edit made."
                                return@runOnUiThread
                            }
                            result.onFailure {
                                status.text = it.message ?: "Free AI response refused; no edit made."
                            }.onSuccess { reply ->
                                runCatching {
                                    WorkspaceStructuredEdit.prepare(files, tasks, projects, projectId, reply)
                                }.onSuccess { candidate ->
                                    if (candidate.context.path != prepared.context.path ||
                                        candidate.context.fileSha256 != prepared.context.fileSha256 ||
                                        candidate.context.specToken != prepared.context.specToken ||
                                        candidate.context.taskId != prepared.context.taskId) {
                                        clearDraft()
                                        status.text = "AI suggestion changed file or scope; refused. No edit made."
                                    } else {
                                        val saved = runCatching {
                                            savedSuggestions.save(files, tasks, projects, projectId, reply, candidate)
                                        }
                                        jsonInput.setText(reply)
                                        draft = candidate
                                        preview.text = candidate.displayText()
                                        preview.visibility = View.VISIBLE
                                        applyButton.visibility = View.VISIBLE
                                        restoreButton.visibility = if (saved.isSuccess) View.VISIBLE else View.GONE
                                        discardButton.visibility = if (saved.isSuccess) View.VISIBLE else View.GONE
                                        status.text = if (saved.isSuccess)
                                            "Free AI suggested one edit. Private 24h draft saved; UNTRUSTED preview only. Separately approve any write."
                                        else
                                            "Free AI suggested one edit, but private save failed. Preview only; keep this screen open. No edit made."
                                    }
                                }.onFailure {
                                    clearDraft()
                                    status.text = "Free AI suggestion rejected by local checks: ${it.message ?: "invalid patch"}. No edit made."
                                }
                            }
                        }
                    }
                })
            })
    }

    private fun validateDraft() {
        runCatching {
            WorkspaceStructuredEdit.prepare(files, tasks, projects, projectId, jsonInput.text.toString())
        }.onSuccess {
            draft = it
            preview.text = it.displayText()
            preview.visibility = View.VISIBLE
            applyButton.visibility = View.VISIBLE
        }.onFailure {
            clearDraft()
            error(it)
        }
    }

    private fun confirmApply() {
        val prepared = draft ?: return
        val fresh = runCatching {
            WorkspaceStructuredEdit.prepare(files, tasks, projects, projectId, jsonInput.text.toString())
        }.getOrElse {
            clearDraft()
            error(it)
            return
        }
        if (fresh.proposal != prepared.proposal || fresh.context.specToken != prepared.context.specToken) {
            clearDraft()
            Toast.makeText(this, "Patch or source changed; preview again", Toast.LENGTH_LONG).show()
            return
        }
        showDialog(AlertDialog.Builder(this)
            .setTitle("Allow THIS exact one-file write?")
            .setMessage(
                "File: ${fresh.proposal.path}\nCurrent SHA-256: ${fresh.proposal.baseSha256}\n\n" +
                    "Replace:\n${fresh.proposal.oldText}\n\nWith:\n${fresh.proposal.newText}\n\n" +
                    "LYRA will save a private rollback first. This approval does NOT authorize " +
                    "another file, provider call, payment, build or completion claim."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply exact edit") { _, _ ->
                runCatching {
                    WorkspaceScopedEdit.apply(files, tasks, projects, fresh.context, fresh.proposal)
                }.onSuccess {
                    runCatching { savedSuggestions.discard(projectId) }
                    Toast.makeText(this, "Structured edit saved and checked; Undo available", Toast.LENGTH_LONG).show()
                    render()
                }.onFailure {
                    render()
                    error(it)
                }
            })
    }
}
