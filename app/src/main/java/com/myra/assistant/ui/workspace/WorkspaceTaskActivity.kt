package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivityWorkspaceTaskBinding
import java.io.File

/** Phase 5 task entry: saved specification and read-only inventory, NOT an autonomous coding executor. */
class WorkspaceTaskActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PROJECT_ID = "workspace_project_id"
        fun intent(context: Context, projectId: String): Intent =
            Intent(context, WorkspaceTaskActivity::class.java).putExtra(EXTRA_PROJECT_ID, projectId)
    }

    private lateinit var binding: ActivityWorkspaceTaskBinding
    private val projects by lazy { WorkspaceProjectStore(File(filesDir, "workspace/projects")) }
    private val tasks by lazy { WorkspaceTaskStore(projects) }
    private val files by lazy { WorkspaceFileStore(projects) }
    private lateinit var projectId: String
    private lateinit var project: WorkspaceProject
    private var loadedBrief = false
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
        binding.taskInspect.setOnClickListener { inspectProject() }
        binding.taskFiles.setOnClickListener {
            guardUnsavedBrief { startActivity(WorkspaceEditorActivity.intent(this, projectId)) }
        }
        binding.taskPreview.visibility = if (project.type == WorkspaceProjectType.WEBSITE) View.VISIBLE else View.GONE
        binding.taskPreview.setOnClickListener {
            guardUnsavedBrief { startActivity(WorkspacePreviewActivity.intent(this, projectId)) }
        }
        val statusWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateStatus()
                // An already displayed inventory is never presented as a fresh snapshot after edits.
                binding.taskInspection.visibility = View.GONE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        binding.taskGoalInput.addTextChangedListener(statusWatcher)
        binding.taskAcceptanceInput.addTextChangedListener(statusWatcher)
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

    /** Keep both task confirmations in LYRA's dark-green palette with legible actions. */
    private fun AlertDialog.Builder.showTaskConfirmation() {
        val dialog = create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_workspace_dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.rgb(190, 255, 202))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.rgb(255, 190, 180))
    }

    private fun isDirty() = WorkspaceTaskDraftPolicy.isDirty(
        binding.taskGoalInput.text.toString(), currentTask?.goal,
        binding.taskAcceptanceInput.text.toString(), currentTask?.acceptanceCriteria,
    )

    /** Neither visible field may be silently lost on back, navigation or pause. */
    private fun guardUnsavedBrief(next: () -> Unit) {
        if (!isDirty()) {
            next()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Unsaved task brief")
            .setMessage("Save the brief and acceptance criteria first, or discard these edits and keep the last saved task unchanged.")
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Discard edits") { _, _ ->
                binding.taskGoalInput.setText(currentTask?.goal.orEmpty())
                binding.taskAcceptanceInput.setText(currentTask?.acceptanceCriteria.orEmpty())
                next()
            }
            .showTaskConfirmation()
    }

    private fun saveGoal() {
        val normalized = runCatching { WorkspaceTaskContract.normalizeGoal(binding.taskGoalInput.text.toString()) }.getOrElse {
            binding.taskGoalInput.error = it.message
            return
        }
        val criteria = runCatching {
            WorkspaceTaskContract.normalizeAcceptanceCriteria(binding.taskAcceptanceInput.text.toString())
        }.getOrElse {
            binding.taskAcceptanceInput.error = it.message
            return
        }
        val existing = tasks.get(projectId)
        if (existing != null && existing.goal != normalized) {
            AlertDialog.Builder(this)
                .setTitle("Replace saved task?")
                .setMessage("The previous task brief and acceptance criteria will be replaced. Project files and personal memory stay unchanged.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace") { _, _ -> persistGoal(normalized, criteria, true) }
                .showTaskConfirmation()
        } else if (existing == null) persistGoal(normalized, criteria, false)
        else if (existing.acceptanceCriteria != criteria) {
            runCatching { tasks.updateAcceptanceCriteria(projectId, criteria) }
                .onSuccess {
                    binding.taskGoalInput.setText(it.goal)
                    binding.taskAcceptanceInput.setText(it.acceptanceCriteria)
                    Toast.makeText(this, "Acceptance criteria saved locally", Toast.LENGTH_SHORT).show()
                    render()
                }.onFailure { Toast.makeText(this, it.message ?: "Criteria could not be saved", Toast.LENGTH_LONG).show() }
        } else {
            binding.taskGoalInput.setText(existing.goal)
            binding.taskAcceptanceInput.setText(existing.acceptanceCriteria)
            Toast.makeText(this, "Task is already saved", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    private fun persistGoal(goal: String, criteria: String, replace: Boolean) {
        runCatching { tasks.create(projectId, goal, replaceExisting = replace, rawAcceptanceCriteria = criteria) }
            .onSuccess {
                binding.taskGoalInput.setText(it.goal)
                binding.taskAcceptanceInput.setText(it.acceptanceCriteria)
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

    /** Read existing project-local names only; do not grant approval or persist fake tool evidence. */
    private fun inspectProject() {
        if (isDirty()) {
            Toast.makeText(this, "Save or discard your task edits before inspecting", Toast.LENGTH_LONG).show()
            return
        }
        val saved = tasks.get(projectId)
        if (saved == null) {
            Toast.makeText(this, "Save a task brief before inspecting", Toast.LENGTH_SHORT).show()
            return
        }
        if (saved.taskId != currentTask?.taskId || saved.goal != currentTask?.goal ||
            saved.acceptanceCriteria != currentTask?.acceptanceCriteria) {
            render()
            Toast.makeText(this, "Saved task changed; review it before inspecting", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { WorkspaceProjectInspection.scan(files, projectId) }
            .onSuccess {
                binding.taskInspection.text = it.displayText()
                binding.taskInspection.visibility = View.VISIBLE
            }.onFailure {
                binding.taskInspection.visibility = View.GONE
                Toast.makeText(this, it.message ?: "Project could not be inspected", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateStatus() {
        binding.taskStatus.text = when {
            isDirty() && currentTask == null -> "Unsaved brief  •  Tap Save task brief"
            isDirty() -> "Unsaved edits  •  Last saved task is unchanged"
            currentTask == null -> "No task saved yet"
            currentTask?.status == WorkspaceTaskStatus.PAUSED -> "Paused  •  Project task stays saved"
            currentTask?.acceptanceCriteria.isNullOrBlank() -> "Saved draft  •  Add acceptance criteria before execution"
            else -> "Saved spec  •  Awaiting LYRA coding worker"
        }
    }

    private fun render() {
        currentTask = tasks.get(projectId)
        val task = currentTask
        // Returning from Files or Preview must not overwrite either unsaved text field.
        if (!loadedBrief) {
            binding.taskGoalInput.setText(task?.goal.orEmpty())
            binding.taskAcceptanceInput.setText(task?.acceptanceCriteria.orEmpty())
            loadedBrief = true
        }
        if (task == null) {
            binding.taskPause.visibility = View.GONE
            binding.taskInspect.visibility = View.GONE
            binding.taskSave.text = "Save task brief"
        } else {
            binding.taskSave.text = "Update task brief"
            binding.taskPause.visibility = View.VISIBLE
            binding.taskInspect.visibility = View.VISIBLE
            binding.taskPause.text = if (task.status == WorkspaceTaskStatus.PAUSED) "Resume task" else "Pause task"
        }
        // File inventory is a tap-time snapshot, never a persisted plan step or success claim.
        binding.taskInspection.visibility = View.GONE
        updateStatus()
        binding.taskPlan.text = WorkspaceTaskContract.steps(project.type).mapIndexed { i, step ->
            "${i + 1}. ${step.intent}\n   ${step.lane.name.lowercase().replace('_', ' ')}  •  ${if (step.approvalRequired) "Approval required" else "Evidence required"}"
        }.joinToString("\n\n")
        binding.taskNotice.text = "Planning checklist only. No AI edits or builds run yet. LYRA must inspect the project, obtain approval and verify real results before reporting completion."
    }
}
