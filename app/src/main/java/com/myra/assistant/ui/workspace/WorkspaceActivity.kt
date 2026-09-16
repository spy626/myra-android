package com.myra.assistant.ui.workspace

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivityWorkspaceBinding
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Workspace owns project UI, not LYRA's personal AIRI memory or voice runtime. */
class WorkspaceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWorkspaceBinding
    private val projectStore by lazy {
        WorkspaceProjectStore(File(filesDir, "workspace/projects"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.newProjectButton.setOnClickListener { showNewProjectDialog() }
    }

    override fun onResume() {
        super.onResume()
        renderRecentProjects()
    }

    private fun showNewProjectDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_new_workspace_project, null)
        val nameInput = content.findViewById<EditText>(R.id.projectNameInput)
        val typeGroup = content.findViewById<RadioGroup>(R.id.projectTypeGroup)
        val dialog = AlertDialog.Builder(this).setView(content).create()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val horizontalMargin = (32 * resources.displayMetrics.density).toInt()
            setLayout(
                (resources.displayMetrics.widthPixels - horizontalMargin).coerceAtLeast(0),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        content.findViewById<TextView>(R.id.cancelProjectButton).setOnClickListener {
            dialog.dismiss()
        }
        content.findViewById<TextView>(R.id.createProjectButton).setOnClickListener {
            val type = when (typeGroup.checkedRadioButtonId) {
                R.id.projectTypeAndroid -> WorkspaceProjectType.ANDROID_APP
                else -> WorkspaceProjectType.WEBSITE
            }
            try {
                val project = projectStore.createProject(nameInput.text?.toString().orEmpty(), type)
                dialog.dismiss()
                openProject(project.projectId)
            } catch (error: IllegalArgumentException) {
                nameInput.error = error.message ?: "Check the project name"
                nameInput.requestFocus()
            } catch (error: Exception) {
                Toast.makeText(
                    this,
                    error.message ?: "Project could not be created",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun renderRecentProjects() {
        val projects = projectStore.listProjects()
        binding.recentProjectsContainer.removeAllViews()
        binding.emptyRecentState.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        binding.recentProjectsContainer.visibility = if (projects.isEmpty()) View.GONE else View.VISIBLE

        projects.take(8).forEach { project ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_workspace_project, binding.recentProjectsContainer, false)
            row.findViewById<TextView>(R.id.projectNameText).text = project.name
            row.findViewById<TextView>(R.id.projectTypeText).text = project.type.displayName
            row.findViewById<TextView>(R.id.projectLastOpenedText).text = buildString {
                append("Last opened ")
                append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.lastOpenedAtMs)))
            }
            row.contentDescription = "Open ${project.name}, ${project.type.displayName}"
            row.setOnClickListener { openProject(project.projectId) }
            binding.recentProjectsContainer.addView(row)
        }
    }

    private fun openProject(projectId: String) {
        startActivity(WorkspaceProjectActivity.intent(this, projectId))
    }
}
