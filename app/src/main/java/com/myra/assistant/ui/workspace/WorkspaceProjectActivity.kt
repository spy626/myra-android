package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
        infoExpanded = savedInstanceState?.getBoolean(STATE_INFO_EXPANDED) ?: false
        renderProjectInfo()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INFO_EXPANDED, infoExpanded)
        super.onSaveInstanceState(outState)
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
