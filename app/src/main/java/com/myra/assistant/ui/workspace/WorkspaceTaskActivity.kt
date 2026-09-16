package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.databinding.ActivityWorkspaceTaskBinding
import java.io.File

/** Phase 5 task entry: saved plan guidance, NOT an autonomous coding executor. */
class WorkspaceTaskActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PROJECT_ID = "workspace_project_id"
        fun intent(context: Context, projectId: String): Intent =
            Intent(context, WorkspaceTaskActivity::class.java).putExtra(EXTRA_PROJECT_ID, projectId)
    }

    private lateinit var binding: ActivityWorkspaceTaskBinding
    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private val tasks by lazy { WorkspaceTaskStore(projects) }
    private lateinit var projectId: String
    private lateinit var project: WorkspaceProject
    private var loadedGoal = false
    private var currentTask: WorkspaceTask? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkspaceTaskBinding.inflate(layoutInflater)
        setContentView(binding.root)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
        val found = projects.getProject(projectId)
        if (found == null) {
            Toast.makeText(this, "Workspace project is unavailable", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        project = found
        binding.taskBack.setOnClickListener { leaveTask() }
        binding.taskHeading.text = project.name
        binding.taskSave.setOnClickListener { saveGoal() }
        binding.taskPause.setOnClickListener { guardUnsavedBrief { togglePause() } }
        binding.taskFiles.setOnClickListener {
            guardUnsavedBrief { startActivity(WorkspaceEditorActivity.intent(this, projectId)) }
        }
        binding.taskPreview.visibility = if (project.type == WorkspaceProjectType.WEBSITE) View.VISIBLE else View.GONE
        binding.taskPreview.setOnClickListener {
            guardUnsavedBrief { startActivity(WorkspacePreviewActivity.intent(this, projectId)) }
        }
        binding.taskGoalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateStatus()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::projectId.isInitialized && ::project.isInitialized) render()
    }

    override fun onBackPressed() {
        leaveTask()
    }

    private fun leaveTask() = guardUnsavedBrief { finish() }

    /** A visible but unsaved brief is never silently replaced by an older saved brief. */
    private fun guardUnsavedBrief(next: () -> Unit) {
        if (!WorkspaceTaskDraftPolicy.isDirty(binding.taskGoalInput.text.toString(), currentTask?.goal)) {
            next()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Unsaved task brief")
            .setMessage("Save the brief first, or discard these edits and keep the last saved task unchanged.")
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Discard edits") { _, _ ->
                binding.taskGoalInput.setText(currentTask?.goal.orEmpty())
                next()
            }
            .show()
    }

    private fun saveGoal() {
        val text = binding.taskGoalInput.text.toString()
        val normalized = runCatching { WorkspaceTaskContract.normalizeGoal(text) }.getOrElse {
            binding.taskGoalInput.error = it.message
            return
        }
        val existing = tasks.get(projectId)
        if (existing != null && existing.goal != normalized) {
            AlertDialog.Builder(this)
                .setTitle("Replace saved task?")
                .setMessage("The previous task brief will be replaced. Project files and personal memory stay unchanged.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace") { _, _ -> persistGoal(normalized, true) }
                .show()
        } else if (existing == null) persistGoal(normalized, false)
        else {
            binding.taskGoalInput.setText(existing.goal)
            Toast.makeText(this, "Task is already saved", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    private fun persistGoal(goal: String, replace: Boolean) {
        runCatching { tasks.create(projectId, goal, replaceExisting = replace) }
            .onSuccess {
                binding.taskGoalInput.setText(it.goal)
                Toast.makeText(this, "Task brief saved locally", Toast.LENGTH_SHORT).show()
                render()
            }.onFailure { Toast.makeText(this, it.message ?: "Task could not be saved", Toast.LENGTH_LONG).show() }
    }

    private fun togglePause() {
        val existing = tasks.get(projectId) ?: return
        runCatching { tasks.setPaused(projectId, existing.status != WorkspaceTaskStatus.PAUSED) }
            .onSuccess { render() }
            .onFailure { Toast.makeText(this, it.message ?: "Cannot update task", Toast.LENGTH_SHORT).show() }
    }

    private fun updateStatus() {
        val dirty = WorkspaceTaskDraftPolicy.isDirty(binding.taskGoalInput.text.toString(), currentTask?.goal)
        binding.taskStatus.text = when {
            dirty && currentTask == null -> "Unsaved brief  •  Tap Save task brief"
            dirty -> "Unsaved edits  •  Last saved task is unchanged"
            currentTask == null -> "No task saved yet"
            currentTask?.status == WorkspaceTaskStatus.PAUSED -> "Paused  •  Project task stays saved"
            else -> "Saved draft  •  Awaiting LYRA coding worker"
        }
    }

    private fun render() {
        currentTask = tasks.get(projectId)
        val task = currentTask
        // Never overwrite an unsaved edit on returning from another app or after a pause/resume.
        if (!loadedGoal) {
            binding.taskGoalInput.setText(task?.goal.orEmpty())
            loadedGoal = true
        }
        if (task == null) {
            binding.taskPause.visibility = View.GONE
            binding.taskSave.text = "Save task brief"
        } else {
            binding.taskSave.text = "Update task brief"
            binding.taskPause.visibility = View.VISIBLE
            binding.taskPause.text = if (task.status == WorkspaceTaskStatus.PAUSED) "Resume task" else "Pause task"
        }
        updateStatus()
        binding.taskPlan.text = WorkspaceTaskContract.steps(project.type).mapIndexed { i, step ->
            "${i + 1}. ${step.intent}\n   ${step.lane.name.lowercase().replace('_', ' ')}  •  ${if (step.approvalRequired) "Approval required" else "Evidence required"}"
        }.joinToString("\n\n")
        binding.taskNotice.text = "Planning checklist only. No AI edits or builds run yet. LYRA must inspect the project, obtain approval and verify real results before reporting completion."
    }
}
