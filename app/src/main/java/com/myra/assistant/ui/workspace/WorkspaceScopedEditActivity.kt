package com.myra.assistant.ui.workspace

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
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import java.io.File

/** Human-entered single-file edit trial; not an AI coding worker or build verification screen. */
class WorkspaceScopedEditActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PROJECT_ID = "workspace_project_id"
        fun intent(context: Context, projectId: String): Intent =
            Intent(context, WorkspaceScopedEditActivity::class.java).putExtra(EXTRA_PROJECT_ID, projectId)
    }

    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private val tasks by lazy { WorkspaceTaskStore(projects) }
    private val files by lazy { WorkspaceFileStore(projects) }
    private lateinit var projectId: String
    private lateinit var status: TextView
    private lateinit var choose: TextView
    private lateinit var fileSummary: TextView
    private lateinit var oldInput: EditText
    private lateinit var newInput: EditText
    private lateinit var preview: TextView
    private lateinit var previewButton: TextView
    private lateinit var applyButton: TextView
    private lateinit var undoButton: TextView
    private lateinit var keepButton: TextView
    private var context: WorkspaceSourceContext.Draft? = null
    private var proposal: WorkspaceScopedEdit.Proposal? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(217, 243, 222))
        textSize = 13f
        setPadding(dp(5), dp(12), dp(5), dp(8))
    }
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
    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        setTextColor(Color.rgb(241, 255, 243))
        setHintTextColor(Color.rgb(140, 167, 149))
        textSize = 14f
        setBackgroundResource(R.drawable.bg_workspace_dialog_input)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(500))
        minLines = 2
        maxLines = 5
        setPadding(dp(14), dp(12), dp(14), dp(12))
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
    private fun dialog(builder: AlertDialog.Builder) {
        val shown = builder.create()
        shown.show()
        shown.window?.setBackgroundDrawableResource(R.drawable.bg_workspace_dialog)
        shown.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.rgb(190, 255, 202))
        shown.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.rgb(190, 255, 202))
    }
    private fun error(throwable: Throwable) {
        Toast.makeText(this, throwable.message ?: "Operation refused; project unchanged or recovery retained", Toast.LENGTH_LONG).show()
    }

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
        column.addView(label("SAFE EDIT TRIAL  •  ${project.name}").apply {
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        column.addView(label("Manual exact-text edit only. No AI generation, provider upload, or build. " +
            "Planning approval is NOT write permission: each edit needs its own confirmation. " +
            "One rollback slot is kept in private project storage."))
        status = panel().also(column::addView)
        choose = button("Choose one project file").also(column::addView)
        choose.setOnClickListener { chooseFile() }
        fileSummary = panel().also(column::addView)
        column.addView(label("EXACT TEXT TO REPLACE (must occur once)"))
        oldInput = input("Paste a short, unique snippet from the selected file").also(column::addView)
        column.addView(label("REPLACEMENT TEXT"))
        newInput = input("Type the replacement yourself").also(column::addView)
        previewButton = button("Preview one-file edit").also(column::addView)
        previewButton.setOnClickListener { prepareProposal() }
        preview = panel().also(column::addView)
        applyButton = button("Review permission & apply this exact edit").also(column::addView)
        applyButton.setOnClickListener { confirmApply() }
        undoButton = button("Undo last safe edit").also(column::addView)
        undoButton.setOnClickListener {
            dialog(AlertDialog.Builder(this).setTitle("Restore saved original?")
                .setMessage("Restore only if the file still matches this edit. Later manual changes will NOT be overwritten.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Undo") { _, _ ->
                    runCatching { WorkspaceScopedEdit.undo(files, projects, projectId) }
                        .onSuccess { render(); Toast.makeText(this, "Original file restored and checked", Toast.LENGTH_LONG).show() }
                        .onFailure(::error)
                })
        }
        keepButton = button("Keep change & release undo slot").also(column::addView)
        keepButton.setOnClickListener {
            dialog(AlertDialog.Builder(this).setTitle("Keep edited file?")
                .setMessage("This deletes its private rollback backup. The current file remains edited.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Keep change") { _, _ ->
                    runCatching { WorkspaceScopedEdit.keep(files, projects, projectId) }
                        .onSuccess { render(); Toast.makeText(this, "Change kept; undo backup cleared", Toast.LENGTH_LONG).show() }
                        .onFailure(::error)
                })
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                proposal = null
                preview.text = ""
                preview.visibility = View.GONE
                applyButton.visibility = View.GONE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        oldInput.addTextChangedListener(watcher)
        newInput.addTextChangedListener(watcher)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) render()
    }

    private fun clearSelection() {
        context = null
        proposal = null
        fileSummary.text = ""
        fileSummary.visibility = View.GONE
        oldInput.visibility = View.GONE
        newInput.visibility = View.GONE
        previewButton.visibility = View.GONE
        preview.text = ""
        preview.visibility = View.GONE
        applyButton.visibility = View.GONE
    }

    private fun render() {
        clearSelection() // Every return from Files/another app must require a fresh snapshot and explicit approval.
        val pending = runCatching { WorkspaceScopedEdit.pending(projects, projectId) }
        if (pending.isFailure) {
            status.text = "Rollback data needs attention. Nothing will be overwritten automatically."
            choose.visibility = View.GONE
            undoButton.visibility = View.GONE
            keepButton.visibility = View.GONE
            return
        }
        val backup = pending.getOrNull()
        undoButton.visibility = if (backup != null) View.VISIBLE else View.GONE
        keepButton.visibility = if (backup != null) View.VISIBLE else View.GONE
        if (backup != null) {
            status.text = "PENDING LOCAL ROLLBACK\nFile: ${backup.path}\n" +
                "Original SHA-256: ${backup.beforeSha256}\nEdited SHA-256: ${backup.afterSha256}\n" +
                "Undo or Keep before a new edit. No build/preview verification claimed."
            choose.visibility = View.GONE
            return
        }
        val task = tasks.get(projectId)
        val ready = task != null && WorkspaceTaskContract.isSpecApproved(task) &&
            task.status != WorkspaceTaskStatus.PAUSED
        status.text = if (ready) "Saved spec approved for planning. Select one file; " +
            "write permission will be requested separately for this exact edit." else
            "Save, approve and resume a task brief first. No file edits authorized."
        choose.visibility = if (ready) View.VISIBLE else View.GONE
    }

    private fun chooseFile() {
        val task = tasks.get(projectId) ?: return
        if (!WorkspaceTaskContract.isSpecApproved(task) || task.status == WorkspaceTaskStatus.PAUSED) {
            render()
            return
        }
        runCatching { WorkspaceSourceContext.choices(files, projectId) }.onSuccess { choices ->
            if (choices.isEmpty()) {
                Toast.makeText(this, "No eligible project text files", Toast.LENGTH_LONG).show()
                return@onSuccess
            }
            val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, choices) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also {
                        (it as TextView).setTextColor(Color.rgb(217, 243, 222))
                    }
            }
            dialog(AlertDialog.Builder(this).setTitle("Choose one file (local; not sent)")
                .setAdapter(adapter) { _, index ->
                    runCatching { WorkspaceLocalReview.prepare(files, tasks, projectId,
                        projects.getProject(projectId)?.type ?: error("Project missing"), task, choices[index]) }
                        .onSuccess { review ->
                            clearSelection()
                            context = review.context
                            fileSummary.text = "FILE: ${review.context.path}\n" +
                                "Full-file SHA-256: ${review.context.fileSha256}\n" +
                                "Saved goal: ${review.plan.goal}\n" +
                                "Read-only plan template; not AI-generated. Enter one unique snippet below."
                            fileSummary.visibility = View.VISIBLE
                            oldInput.setText("")
                            newInput.setText("")
                            oldInput.visibility = View.VISIBLE
                            newInput.visibility = View.VISIBLE
                            previewButton.visibility = View.VISIBLE
                        }.onFailure(::error)
                }.setNegativeButton("Cancel", null))
        }.onFailure(::error)
    }

    private fun prepareProposal() {
        val selected = context ?: return
        runCatching { WorkspaceScopedEdit.propose(files, tasks, projects, selected,
            oldInput.text.toString(), newInput.text.toString()) }
            .onSuccess {
                proposal = it
                preview.text = it.displayText()
                preview.visibility = View.VISIBLE
                applyButton.visibility = View.VISIBLE
            }.onFailure {
                proposal = null
                preview.visibility = View.GONE
                applyButton.visibility = View.GONE
                error(it)
            }
    }

    private fun confirmApply() {
        val selected = context ?: return
        val prepared = proposal ?: return
        if (oldInput.text.toString() != prepared.oldText || newInput.text.toString() != prepared.newText) {
            proposal = null
            preview.visibility = View.GONE
            applyButton.visibility = View.GONE
            Toast.makeText(this, "Text changed; preview the edit again", Toast.LENGTH_LONG).show()
            return
        }
        dialog(AlertDialog.Builder(this).setTitle("Allow THIS one-file write?")
            .setMessage("File: ${prepared.path}\nCurrent SHA-256: ${prepared.baseSha256}\n\n" +
                "Replace:\n${prepared.oldText}\n\nWith:\n${prepared.newText}\n\n" +
                "A private rollback backup will be saved first. " +
                "This does NOT approve another file, AI run, payment or verification.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply exact edit") { _, _ ->
                runCatching { WorkspaceScopedEdit.apply(files, tasks, projects, selected, prepared) }
                    .onSuccess {
                        render()
                        Toast.makeText(this, "File changed and SHA-256 checked. Undo available.", Toast.LENGTH_LONG).show()
                    }.onFailure { failure ->
                        render() // A failed/uncertain write can leave a protected recovery slot.
                        error(failure)
                    }
            })
    }
}
