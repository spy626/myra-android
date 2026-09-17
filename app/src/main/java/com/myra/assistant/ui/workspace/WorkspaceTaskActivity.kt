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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivityWorkspaceTaskBinding
import java.io.File

/** Phase 5 task entry: saved spec, local planning consent and opt-in inspection; NOT an AI executor. */
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
    /** Ephemeral screen-only draft, never stored in task JSON or sent to a provider. */
    private var localContext: WorkspaceSourceContext.Draft? = null
    private var localPlan: WorkspaceProjectPlanDraft.Draft? = null
    private var localReviewNote: WorkspacePlanReviewNote? = null
    private lateinit var localPlanButton: TextView
    private lateinit var localPlanPreview: TextView
    private lateinit var localReviewButton: TextView
    private lateinit var localReviewPreview: TextView

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
        installLocalPlanUi()
        binding.taskBack.setOnClickListener { leaveTask() }
        binding.taskHeading.text = project.name
        binding.taskSave.setOnClickListener { saveGoal() }
        binding.taskSpecApproval.setOnClickListener { changeSpecApproval() }
        binding.taskPause.setOnClickListener { guardUnsavedBrief { togglePause() } }
        binding.taskInspect.setOnClickListener { inspectProject() }
        binding.taskSource.setOnClickListener { reviewSource() }
        // One opt-in file selection prepares both bounded context and the existing review-only plan.
        binding.taskContext.text = "Prepare local review (context + plan)"
        binding.taskContext.setOnClickListener { prepareLocalContext() }
        binding.taskContextRecheck.setOnClickListener { recheckLocalContext() }
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
                // Previously displayed views are stale after unsaved spec edits.
                binding.taskInspection.visibility = View.GONE
                binding.taskSourcePreview.visibility = View.GONE
                clearLocalContext()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        binding.taskGoalInput.addTextChangedListener(statusWatcher)
        binding.taskAcceptanceInput.addTextChangedListener(statusWatcher)
        render()
    }

    /** Keep optional plan and notes next to the existing context controls; no new execution screen. */
    private fun installLocalPlanUi() {
        val column = binding.taskContextRecheck.parent as LinearLayout
        val insertAt = column.indexOfChild(binding.taskContextRecheck) + 1
        fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
        localPlanButton = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(10)
            }
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            gravity = Gravity.CENTER
            text = "Refresh review plan (local only)"
            setTextColor(Color.rgb(212, 248, 217))
            textSize = 13f
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { showLocalPlan() }
        }
        column.addView(localPlanButton, insertAt)
        localPlanPreview = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
            setBackgroundResource(R.drawable.bg_workspace_project_card)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(Color.rgb(217, 243, 222))
            textSize = 12f
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        column.addView(localPlanPreview, insertAt + 1)
        localReviewButton = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(10)
            }
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            gravity = Gravity.CENTER
            text = "Add plan scope note (screen only)"
            setTextColor(Color.rgb(212, 248, 217))
            textSize = 13f
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { addLocalPlanReviewNote() }
        }
        column.addView(localReviewButton, insertAt + 2)
        localReviewPreview = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
            setBackgroundResource(R.drawable.bg_workspace_project_card)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(Color.rgb(217, 243, 222))
            textSize = 12f
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        column.addView(localReviewPreview, insertAt + 3)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && ::projectId.isInitialized && ::project.isInitialized) render()
    }

    override fun onBackPressed() {
        leaveTask()
    }

    private fun leaveTask() = guardUnsavedBrief { finish() }

    /** Keep confirmations and both file choosers in LYRA's readable dark-green palette. */
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
                .setMessage("The previous task brief, acceptance criteria and planning approval will be replaced. Project files and personal memory stay unchanged.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace") { _, _ -> persistGoal(normalized, criteria, true) }
                .showTaskConfirmation()
        } else if (existing == null) persistGoal(normalized, criteria, false)
        else if (existing.acceptanceCriteria != criteria) {
            runCatching { tasks.updateAcceptanceCriteria(projectId, criteria) }
                .onSuccess {
                    binding.taskGoalInput.setText(it.goal)
                    binding.taskAcceptanceInput.setText(it.acceptanceCriteria)
                    Toast.makeText(this, "Criteria saved; previous spec approval cleared", Toast.LENGTH_SHORT).show()
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

    /** Only the visible, saved, unedited revision can be approved or revoked. */
    private fun changeSpecApproval() {
        if (isDirty()) {
            Toast.makeText(this, "Save or discard edits before approval", Toast.LENGTH_LONG).show()
            return
        }
        val saved = tasks.get(projectId)
        if (saved == null || saved.taskId != currentTask?.taskId ||
            saved.specRevision != currentTask?.specRevision || saved.goal != currentTask?.goal ||
            saved.acceptanceCriteria != currentTask?.acceptanceCriteria ||
            saved.approvedSpecToken != currentTask?.approvedSpecToken) {
            render()
            Toast.makeText(this, "Saved spec changed; review it before approval", Toast.LENGTH_LONG).show()
            return
        }
        if (WorkspaceTaskContract.isSpecApproved(saved)) {
            persistSpecApproval(saved, false)
            return
        }
        if (saved.acceptanceCriteria.isBlank()) {
            Toast.makeText(this, "Add and save acceptance criteria first", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Approve saved spec for planning?")
            .setMessage("Goal: ${saved.goal}\n\nAcceptance criteria: ${saved.acceptanceCriteria}\n\nPlanning consent only. No AI runs, file edits, builds, tool permissions or payments are authorized.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Approve spec") { _, _ -> persistSpecApproval(saved, true) }
            .showTaskConfirmation()
    }

    private fun persistSpecApproval(expected: WorkspaceTask, approve: Boolean) {
        if (isDirty()) {
            Toast.makeText(this, "Save or discard edits before approval", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            tasks.setSpecificationApproved(projectId, expected.taskId,
                WorkspaceTaskContract.specToken(expected), approve)
        }.onSuccess {
            render()
            Toast.makeText(this,
                if (approve) "Saved spec approved for planning only" else "Planning approval revoked",
                Toast.LENGTH_SHORT).show()
        }.onFailure {
            render()
            Toast.makeText(this, it.message ?: "Spec approval could not be updated", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePause() {
        val existing = tasks.get(projectId) ?: return
        runCatching { tasks.setPaused(projectId, existing.status != WorkspaceTaskStatus.PAUSED) }
            .onSuccess { render() }
            .onFailure { Toast.makeText(this, it.message ?: "Cannot update task", Toast.LENGTH_SHORT).show() }
    }

    /** A saved task is a prerequisite; draft text is never silently used as a saved specification. */
    private fun currentSavedForInspection(): WorkspaceTask? {
        if (isDirty()) {
            Toast.makeText(this, "Save or discard your task edits before inspecting", Toast.LENGTH_LONG).show()
            return null
        }
        val saved = tasks.get(projectId)
        if (saved == null) {
            Toast.makeText(this, "Save a task brief before inspecting", Toast.LENGTH_SHORT).show()
            return null
        }
        if (saved.taskId != currentTask?.taskId || saved.goal != currentTask?.goal ||
            saved.acceptanceCriteria != currentTask?.acceptanceCriteria) {
            render()
            Toast.makeText(this, "Saved task changed; review it before inspecting", Toast.LENGTH_LONG).show()
            return null
        }
        return saved
    }

    /** Names only: no provider, file mutation, approval or fake tool evidence. */
    private fun inspectProject() {
        if (currentSavedForInspection() == null) return
        runCatching { WorkspaceProjectInspection.scan(files, projectId) }
            .onSuccess {
                binding.taskSourcePreview.visibility = View.GONE
                clearLocalContext()
                binding.taskInspection.text = it.displayText()
                binding.taskInspection.visibility = View.VISIBLE
            }.onFailure {
                binding.taskInspection.visibility = View.GONE
                Toast.makeText(this, it.message ?: "Project could not be inspected", Toast.LENGTH_LONG).show()
            }
    }

    /** User selects one current text file for a bounded, local-only preview. */
    private fun reviewSource() {
        val saved = currentSavedForInspection() ?: return
        runCatching { WorkspaceSourcePreview.choices(files, projectId) }
            .onSuccess { choices ->
                if (choices.isEmpty()) {
                    binding.taskSourcePreview.visibility = View.GONE
                    Toast.makeText(this, "No project files available to preview", Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }
                val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, choices) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                        super.getView(position, convertView, parent).also { view ->
                            (view as TextView).setTextColor(Color.rgb(217, 243, 222))
                        }
                }
                // AlertDialog shows its message instead of its adapter list when both are supplied.
                // Put the read-only notice in the title so the actual file choices remain visible.
                AlertDialog.Builder(this)
                    .setTitle("Choose a project file (read-only)")
                    .setAdapter(adapter) { _, which -> showSourcePreview(choices[which], saved) }
                    .setNegativeButton("Cancel", null)
                    .showTaskConfirmation()
            }.onFailure { Toast.makeText(this, "Cannot list project files", Toast.LENGTH_LONG).show() }
    }

    private fun showSourcePreview(selectedPath: String, expected: WorkspaceTask) {
        val saved = currentSavedForInspection() ?: return
        if (saved.taskId != expected.taskId || saved.goal != expected.goal ||
            saved.acceptanceCriteria != expected.acceptanceCriteria) {
            binding.taskSourcePreview.visibility = View.GONE
            Toast.makeText(this, "Task changed; reopen the file chooser", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { WorkspaceSourcePreview.read(files, projectId, selectedPath) }
            .onSuccess {
                binding.taskInspection.visibility = View.GONE
                clearLocalContext()
                binding.taskSourcePreview.text = it.displayText()
                binding.taskSourcePreview.visibility = View.VISIBLE
            }.onFailure {
                binding.taskSourcePreview.visibility = View.GONE
                Toast.makeText(this, it.message ?: "File cannot be previewed", Toast.LENGTH_LONG).show()
            }
    }

    /** Explicit file selection and conservative full-source scan; nothing is sent to a model. */
    private fun prepareLocalContext() {
        val saved = currentSavedForInspection() ?: return
        if (!WorkspaceTaskContract.isSpecApproved(saved) || saved.status == WorkspaceTaskStatus.PAUSED) {
            Toast.makeText(this, "Approve the saved spec and resume before preparing context", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { WorkspaceSourceContext.choices(files, projectId) }
            .onSuccess { choices ->
                if (choices.isEmpty()) {
                    clearLocalContext()
                    Toast.makeText(this, "No allowed project text files to prepare", Toast.LENGTH_LONG).show()
                    return@onSuccess
                }
                val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, choices) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                        super.getView(position, convertView, parent).also { view ->
                            (view as TextView).setTextColor(Color.rgb(217, 243, 222))
                        }
                }
                AlertDialog.Builder(this)
                    .setTitle("Choose one file for local review (not sent)")
                    .setAdapter(adapter) { _, which -> showLocalContext(choices[which], saved) }
                    .setNegativeButton("Cancel", null)
                    .showTaskConfirmation()
            }.onFailure {
                clearLocalContext()
                Toast.makeText(this, "Cannot list context files", Toast.LENGTH_LONG).show()
            }
    }

    private fun clearLocalPlan() {
        localPlan = null
        localReviewNote = null
        localPlanPreview.text = ""
        localPlanPreview.visibility = View.GONE
        localReviewButton.visibility = View.GONE
        localReviewButton.text = "Add plan scope note (screen only)"
        localReviewPreview.text = ""
        localReviewPreview.visibility = View.GONE
    }

    private fun clearLocalContext() {
        localContext = null
        binding.taskContextPreview.text = ""
        binding.taskContextPreview.visibility = View.GONE
        binding.taskContextRecheck.visibility = View.GONE
        localPlanButton.visibility = View.GONE
        clearLocalPlan()
    }

    /** One explicitly selected file prepares both read-only outputs; fail closed without a partial plan. */
    private fun showLocalContext(selectedPath: String, expected: WorkspaceTask) {
        if (currentSavedForInspection() == null) return
        runCatching { WorkspaceLocalReview.prepare(files, tasks, projectId, project.type, expected, selectedPath) }
            .onSuccess {
                binding.taskInspection.visibility = View.GONE
                binding.taskSourcePreview.visibility = View.GONE
                clearLocalContext()
                localContext = it.context
                localPlan = it.plan
                binding.taskContextPreview.text = it.context.displayText()
                binding.taskContextPreview.visibility = View.VISIBLE
                binding.taskContextRecheck.visibility = View.VISIBLE
                localPlanButton.visibility = View.VISIBLE
                localPlanPreview.text = it.plan.displayText()
                localPlanPreview.visibility = View.VISIBLE
                localReviewButton.visibility = View.VISIBLE
            }.onFailure {
                clearLocalContext()
                Toast.makeText(this, it.message ?: "Local review blocked; inspect file locally", Toast.LENGTH_LONG).show()
            }
    }

    /** A manual snapshot recheck, never a permission to upload, run tools or mark a step verified. */
    private fun recheckLocalContext() {
        val draft = localContext ?: return
        if (isDirty()) {
            clearLocalContext()
            Toast.makeText(this, "Task edits are unsaved; prepare context again", Toast.LENGTH_LONG).show()
            return
        }
        clearLocalPlan()
        if (WorkspaceContextFreshness.check(files, tasks, projectId, draft) ==
            WorkspaceContextFreshness.Result.SAME_CONTENT_AND_SPEC) {
            binding.taskContextPreview.text = draft.displayText() +
                "\n\nRechecked now: same saved spec and complete file content. Local only; not a live check or permission to send."
        } else {
            clearLocalContext()
            binding.taskContextPreview.text = "Context changed or blocked. Prepare a new local context draft before any future use. Nothing was sent."
            binding.taskContextPreview.visibility = View.VISIBLE
        }
    }

    /** Show a template bound to the current approved spec and one fresh file; never request a model. */
    private fun showLocalPlan() {
        val draft = localContext ?: return
        if (isDirty()) {
            clearLocalContext()
            Toast.makeText(this, "Unsaved task edits; prepare context again", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { WorkspaceProjectPlanDraft.prepare(files, tasks, projectId, project.type, draft) }
            .onSuccess {
                clearLocalPlan()
                localPlan = it
                localPlanPreview.text = it.displayText()
                localPlanPreview.visibility = View.VISIBLE
                localReviewButton.visibility = View.VISIBLE
            }.onFailure {
                clearLocalContext()
                binding.taskContextPreview.text = "Context changed or blocked. Prepare a fresh local context before reviewing a plan. Nothing was sent."
                binding.taskContextPreview.visibility = View.VISIBLE
            }
    }

    /** A screen-only user note, not a saved plan, consent, action approval or model prompt. */
    private fun addLocalPlanReviewNote() {
        val context = localContext ?: return
        val plan = localPlan ?: return
        if (isDirty()) {
            clearLocalContext()
            Toast.makeText(this, "Save or discard task edits; prepare the plan again", Toast.LENGTH_LONG).show()
            return
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val input = EditText(this).apply {
            setBackgroundResource(R.drawable.bg_workspace_dialog_input)
            setTextColor(Color.rgb(241, 255, 243))
            setHintTextColor(Color.rgb(140, 167, 149))
            hint = "e.g. Only change the header; keep existing buttons"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(500))
            minLines = 3
            maxLines = 6
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setText(localReviewNote?.note.orEmpty())
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Plan scope note (screen only; not saved)")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Show note") { _, _ ->
                runCatching {
                    WorkspacePlanReviewNote.prepare(files, tasks, projectId, project.type,
                        context, plan, input.text.toString())
                }.onSuccess {
                    localReviewNote = it
                    localReviewPreview.text = it.displayText()
                    localReviewPreview.visibility = View.VISIBLE
                    localReviewButton.text = "Edit plan scope note (screen only)"
                }.onFailure {
                    if (WorkspaceContextFreshness.check(files, tasks, projectId, context) !=
                        WorkspaceContextFreshness.Result.SAME_CONTENT_AND_SPEC) {
                        clearLocalContext()
                        Toast.makeText(this, "Plan changed; prepare fresh local context", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, it.message ?: "Review note was not accepted", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .showTaskConfirmation()
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
        val task = currentTask
        val ready = task != null && task.acceptanceCriteria.isNotBlank() && !isDirty()
        binding.taskSpecApproval.visibility = if (ready) View.VISIBLE else View.GONE
        binding.taskSpecApproval.text = if (task != null && WorkspaceTaskContract.isSpecApproved(task))
            "Revoke spec planning approval" else "Approve saved spec (planning only)"
        binding.taskContext.visibility = if (ready && task != null && WorkspaceTaskContract.isSpecApproved(task) &&
            task.status != WorkspaceTaskStatus.PAUSED) View.VISIBLE else View.GONE
        if (binding.taskContext.visibility != View.VISIBLE) clearLocalContext()
        binding.taskApprovalState.text = when {
            isDirty() -> "Unsaved edits are not approved. Save them first."
            task == null || task.acceptanceCriteria.isBlank() -> "Planning approval pending: save a complete specification."
            WorkspaceTaskContract.isSpecApproved(task) -> "Saved spec approved for planning only. No AI work or file edits authorized."
            else -> "Planning approval pending. No AI work or file edits authorized."
        }
    }

    private fun render() {
        currentTask = tasks.get(projectId)
        val task = currentTask
        if (!loadedBrief) {
            binding.taskGoalInput.setText(task?.goal.orEmpty())
            binding.taskAcceptanceInput.setText(task?.acceptanceCriteria.orEmpty())
            loadedBrief = true
        }
        if (task == null) {
            binding.taskPause.visibility = View.GONE
            binding.taskInspect.visibility = View.GONE
            binding.taskSource.visibility = View.GONE
            binding.taskSave.text = "Save task brief"
        } else {
            binding.taskSave.text = "Update task brief"
            binding.taskPause.visibility = View.VISIBLE
            binding.taskInspect.visibility = View.VISIBLE
            binding.taskSource.visibility = View.VISIBLE
            binding.taskPause.text = if (task.status == WorkspaceTaskStatus.PAUSED) "Resume task" else "Pause task"
        }
        binding.taskInspection.visibility = View.GONE
        binding.taskSourcePreview.visibility = View.GONE
        clearLocalContext()
        updateStatus()
        binding.taskPlan.text = WorkspaceTaskContract.steps(project.type).mapIndexed { i, step ->
            "${i + 1}. ${step.intent}\n   ${step.lane.name.lowercase().replace('_', ' ')}  •  ${if (step.approvalRequired) "Approval required" else "Evidence required"}"
        }.joinToString("\n\n")
        binding.taskNotice.text = "Planning checklist only. No AI edits or builds run yet. LYRA must inspect the project, obtain separate action approval and verify real results before reporting completion."
    }
}
