package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
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
import java.io.File

/** Offline contract trial: validates model-style structured output, then reuses the proven Safe Edit write/rollback path. */
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
    private lateinit var projectId: String
    private lateinit var status: TextView
    private lateinit var jsonInput: EditText
    private lateinit var preview: TextView
    private lateinit var validateButton: TextView
    private lateinit var applyButton: TextView
    private lateinit var undoButton: TextView
    private lateinit var keepButton: TextView
    private var draft: WorkspaceStructuredEdit.Draft? = null

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
        column.addView(label("STRUCTURED PATCH TRIAL  •  ${project.name}").apply {
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        column.addView(label(
            "Paste a model-style JSON edit. LYRA treats every field as untrusted data, " +
                "re-reads the current project locally, and refuses stale or blocked scope. " +
                "This screen makes NO network request and gives NO automatic write authority."
        ))
        status = panel().also(column::addView)
        column.addView(label(
            "JSON format:\n" +
                "{\"schemaVersion\":1,\"operation\":\"replace_exact_once\",\"path\":\"index.html\"," +
                "\"oldText\":\"Hello\",\"newText\":\"Welcome\",\"rationale\":\"Why this matches the saved spec\"}"
        ))
        jsonInput = EditText(this).apply {
            hint = "Paste structured edit JSON"
            setTextColor(Color.rgb(241, 255, 243))
            setHintTextColor(Color.rgb(140, 167, 149))
            textSize = 13f
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(6000))
            minLines = 7
            maxLines = 14
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(jsonInput)
        validateButton = button("Validate current scope & preview patch").also(column::addView)
        preview = panel().also(column::addView)
        applyButton = button("Review permission & apply via Safe Edit").also(column::addView)
        undoButton = button("Undo protected edit").also(column::addView)
        keepButton = button("Keep change & clear rollback").also(column::addView)

        jsonInput.setOnFocusChangeListener { _, _ -> clearDraft() }
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
                            Toast.makeText(this, "Change kept; rollback cleared", Toast.LENGTH_LONG).show()
                            render()
                        }.onFailure(::error)
                })
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) render()
    }

    private fun clearDraft() {
        draft = null
        preview.text = ""
        preview.visibility = View.GONE
        applyButton.visibility = View.GONE
    }

    private fun render() {
        clearDraft()
        val pending = runCatching { WorkspaceScopedEdit.pending(projects, projectId) }
        if (pending.isFailure) {
            status.text = "Rollback data needs attention. No new write will be attempted."
            jsonInput.visibility = View.GONE
            validateButton.visibility = View.GONE
            undoButton.visibility = View.GONE
            keepButton.visibility = View.GONE
            return
        }
        val backup = pending.getOrNull()
        if (backup != null) {
            status.text = "PENDING PROTECTED EDIT\nFile: ${backup.path}\n" +
                "Undo or Keep before another structured patch. No build/preview verification claimed."
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
        status.text = if (ready)
            "Ready for an offline structured edit proposal. Provider send is NOT enabled in this slice."
        else
            "Save, approve and resume the task brief first. No patch or write is authorized."
        jsonInput.visibility = if (ready) View.VISIBLE else View.GONE
        validateButton.visibility = if (ready) View.VISIBLE else View.GONE
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
                    Toast.makeText(this, "Structured edit saved and checked; Undo available", Toast.LENGTH_LONG).show()
                    render()
                }.onFailure {
                    render()
                    error(it)
                }
            })
    }
}
