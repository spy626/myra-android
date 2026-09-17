package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivityWorkspaceProjectBinding
import java.io.File
import java.text.DateFormat
import java.util.Date

class WorkspaceProjectActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PROJECT_ID = "workspace_project_id"
        private const val STATE_INFO_EXPANDED = "workspace_info_expanded"

        fun intent(context: Context, projectId: String): Intent =
            Intent(context, WorkspaceProjectActivity::class.java)
                .putExtra(EXTRA_PROJECT_ID, projectId)
    }

    private lateinit var binding: ActivityWorkspaceProjectBinding
    private val projectStore by lazy {
        WorkspaceProjectStore(File(filesDir, "workspace/projects"))
    }
    private var projectId: String? = null
    private var infoExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspaceProjectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.deleteProjectButton.setOnClickListener { confirmDeleteProject() }
        binding.openEditorButton.setOnClickListener {
            projectId?.let { startActivity(WorkspaceEditorActivity.intent(this, it)) }
        }
        binding.openPreviewButton.setOnClickListener {
            projectId?.let { startActivity(WorkspacePreviewActivity.intent(this, it)) }
        }
        binding.projectInfoToggle.setOnClickListener {
            infoExpanded = !infoExpanded
            renderProjectInfo()
        }

        projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        val project = projectId?.let(projectStore::markOpened)
        if (project == null) {
            Toast.makeText(this, "Workspace project is unavailable", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        bindProject(project)
        addTaskEntry()
        addScopedEditEntry()
        addStructuredEditEntry()
        infoExpanded = savedInstanceState?.getBoolean(STATE_INFO_EXPANDED) ?: false
        renderProjectInfo()
    }

    /** A single entry to the project-scoped task brief; no duplicate planner or AI executor. */
    private fun addTaskEntry() {
        val parent = binding.openPreviewButton.parent as LinearLayout
        val button = TextView(this).apply {
            text = "✦   Task brief & plan   ›"
            contentDescription = "Open project task brief and plan"
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#D4F8D9"))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            isClickable = true
            isFocusable = true
            setOnClickListener { projectId?.let { id -> startActivity(WorkspaceTaskActivity.intent(this@WorkspaceProjectActivity, id)) } }
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (56 * resources.displayMetrics.density + 0.5f).toInt()).apply {
            topMargin = (10 * resources.displayMetrics.density + 0.5f).toInt()
        }
        parent.addView(button, parent.indexOfChild(binding.openPreviewButton) + 1, params)
    }

    /** Opt-in manual edit trial with separate file-specific approval and protected undo. */
    private fun addScopedEditEntry() {
        val parent = binding.openPreviewButton.parent as LinearLayout
        val button = TextView(this).apply {
            text = "✦   Safe edit & Undo (manual trial)   ›"
            contentDescription = "Open one-file manual edit and rollback trial"
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#D4F8D9"))
            textSize = 14f
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                projectId?.let { id -> startActivity(WorkspaceScopedEditActivity.intent(this@WorkspaceProjectActivity, id)) }
            }
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (52 * resources.displayMetrics.density + 0.5f).toInt()).apply {
            topMargin = (10 * resources.displayMetrics.density + 0.5f).toInt()
        }
        parent.addView(button, parent.indexOfChild(binding.openPreviewButton) + 2, params)
    }

    /** Offline model-output boundary; structured data stays untrusted until local checks and explicit write approval. */
    private fun addStructuredEditEntry() {
        val parent = binding.openPreviewButton.parent as LinearLayout
        val button = TextView(this).apply {
            text = "✦   Structured AI patch (offline trial)   ›"
            contentDescription = "Open offline structured AI patch validation trial"
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#D4F8D9"))
            textSize = 14f
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                projectId?.let { id -> startActivity(WorkspaceStructuredEditActivity.intent(this@WorkspaceProjectActivity, id)) }
            }
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (52 * resources.displayMetrics.density + 0.5f).toInt()).apply {
            topMargin = (10 * resources.displayMetrics.density + 0.5f).toInt()
        }
        parent.addView(button, parent.indexOfChild(binding.openPreviewButton) + 3, params)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INFO_EXPANDED, infoExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        projectId?.let { id -> projectStore.getProject(id)?.let(::bindProject) }
    }

    private fun renderProjectInfo() {
        binding.projectInfoPanel.visibility = if (infoExpanded) View.VISIBLE else View.GONE
        binding.projectInfoToggle.text =
            if (infoExpanded) "Hide project information  ▴" else "Project information  ▾"
        binding.projectInfoToggle.contentDescription =
            if (infoExpanded) "Hide project information" else "Show project information"
    }

    private fun bindProject(project: WorkspaceProject) {
        binding.projectTitle.text = project.name
        binding.projectType.text = "${project.type.displayName}  •  ${project.type.subtitle}"
        binding.openPreviewButton.visibility =
            if (project.type == WorkspaceProjectType.WEBSITE) View.VISIBLE else View.GONE
        binding.projectIdText.text = "Project ID\n${project.projectId}"
        binding.projectStorageText.text = "Project root\nPrivate Workspace storage / ${project.rootRelativePath}"
        binding.projectCreatedText.text = "Created\n${formatDate(project.createdAtMs)}"
        binding.projectUpdatedText.text = "Last opened\n${formatDate(project.lastOpenedAtMs)}"
    }

    private fun confirmDeleteProject() {
        val id = projectId ?: return
        val project = projectStore.getProject(id) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete ${project.name}?")
            .setMessage("This removes this Workspace project and its project files from LYRA's private storage. Personal AIRI memory is not affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (projectStore.deleteProject(id)) {
                    Toast.makeText(this, "Project deleted", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this, "Project could not be deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun formatDate(timeMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMs))
}
