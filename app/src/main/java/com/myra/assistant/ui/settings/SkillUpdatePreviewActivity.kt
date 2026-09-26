package com.myra.assistant.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivitySkillUpdatePreviewBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillApprovedUpdate
import com.myra.assistant.ui.workspace.WorkspaceSkillGitHubInstallApproval
import com.myra.assistant.ui.workspace.WorkspaceSkillGitHubInstallRunner
import com.myra.assistant.ui.workspace.WorkspaceSkillGitHubPreviewRunner
import com.myra.assistant.ui.workspace.WorkspaceSkillGitHubReadSession
import com.myra.assistant.ui.workspace.WorkspaceSkillImportPreview
import com.myra.assistant.ui.workspace.WorkspaceSkillStore
import com.myra.assistant.ui.workspace.WorkspaceSkillUpdatePreview
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * H10 source-agnostic update comparison only.
 *
 * Local and pinned GitHub candidates are inspected through existing skill contracts. This activity
 * never calls WorkspaceSkillStore.update(), never creates an update approval, and never writes a
 * candidate package or catalog entry.
 */
class SkillUpdatePreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillUpdatePreviewBinding
    private lateinit var githubRunner: WorkspaceSkillGitHubPreviewRunner
    private lateinit var githubRevalidationRunner: WorkspaceSkillGitHubInstallRunner
    private var localSkillMd: ByteArray? = null
    private var localSkillJson: ByteArray? = null
    private var pendingUpdate: PendingUpdate? = null

    private sealed class PendingUpdate {
        abstract val prepared: WorkspaceSkillApprovedUpdate.Prepared

        data class Local(
            override val prepared: WorkspaceSkillApprovedUpdate.Prepared,
        ) : PendingUpdate()

        data class GitHub(
            override val prepared: WorkspaceSkillApprovedUpdate.Prepared,
            val revalidation: WorkspaceSkillGitHubInstallApproval.Prepared,
        ) : PendingUpdate()
    }

    private val skillStore by lazy {
        WorkspaceSkillStore(File(noBackupFilesDir, WorkspaceSkillStore.APP_DIRECTORY))
    }

    private val skillMdPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            selectFile(uri, "SKILL.md") { bytes ->
                localSkillMd = bytes
                binding.localSkillState.text = "SKILL.md selected · " + bytes.size + " bytes"
                renderLocal()
            }
        }

    private val skillJsonPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            selectFile(uri, "skill.json") { bytes ->
                localSkillJson = bytes
                binding.localManifestState.text =
                    "skill.json selected · " + bytes.size + " bytes"
                binding.clearManifestButton.visibility = View.VISIBLE
                renderLocal()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillUpdatePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        githubRevalidationRunner = WorkspaceSkillGitHubInstallRunner(
            object : WorkspaceSkillGitHubInstallRunner.Listener {
                override fun onStage(message: String) = runOnUiThread {
                    binding.githubProgress.text = message
                }

                override fun onValidated(
                    validated: WorkspaceSkillGitHubInstallApproval.Validated,
                ) = runOnUiThread {
                    val pending = pendingUpdate as? PendingUpdate.GitHub
                    if (pending == null) {
                        setMutationBusy(false)
                        showBlocked(
                            "Update approval stale",
                            "The approved GitHub update is no longer current. Preview again.",
                        )
                        return@runOnUiThread
                    }
                    val candidate = WorkspaceSkillUpdatePreview.Candidate(
                        skill = validated.fresh.skill,
                        snapshot = validated.fresh.snapshot,
                        permissionSha256 = validated.fresh.approval.permissionSha256,
                        packageFiles = validated.fresh.packageFiles,
                    )
                    applyApprovedUpdate(pending.prepared, candidate)
                }

                override fun onError(message: String) = runOnUiThread {
                    setMutationBusy(false)
                    Toast.makeText(
                        this@SkillUpdatePreviewActivity,
                        message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        )

        githubRunner = WorkspaceSkillGitHubPreviewRunner(
            object : WorkspaceSkillGitHubPreviewRunner.Listener {
                override fun onStage(message: String) = runOnUiThread {
                    binding.githubProgress.text = message
                }

                override fun onComplete(completion: WorkspaceSkillGitHubReadSession.Completion) =
                    runOnUiThread {
                        setGithubBusy(false)
                        val candidate = completion.updateCandidate
                        if (candidate == null) {
                            showBlocked(
                                "GitHub candidate blocked",
                                "The pinned candidate failed the existing import/security preview."
                            )
                            return@runOnUiThread
                        }
                        renderCandidate(
                            candidate = candidate,
                            sourceLabel = "Pinned GitHub candidate",
                            githubCompletion = completion,
                        )
                    }

                override fun onError(message: String) = runOnUiThread {
                    setGithubBusy(false)
                    showBlocked("GitHub candidate unavailable", message)
                }
            }
        )

        binding.backButton.setOnClickListener { finish() }
        binding.chooseSkillMdButton.setOnClickListener {
            skillMdPicker.launch(arrayOf("text/*", "application/octet-stream"))
        }
        binding.chooseSkillJsonButton.setOnClickListener {
            skillJsonPicker.launch(
                arrayOf("application/json", "text/*", "application/octet-stream"))
        }
        binding.clearManifestButton.setOnClickListener {
            localSkillJson = null
            binding.localManifestState.text = "No skill.json selected"
            binding.clearManifestButton.visibility = View.GONE
            renderLocal()
        }
        binding.applyUpdateButton.setOnClickListener {
            pendingUpdate?.let(::confirmUpdate)
        }
        binding.githubPreviewButton.setOnClickListener {
            val url = binding.githubUrl.text?.toString().orEmpty().trim()
            if (url.isBlank()) {
                binding.githubUrl.error = "Paste a GitHub repository or SKILL.md link"
                return@setOnClickListener
            }
            hideKeyboard()
            clearResult()
            setGithubBusy(true)
            githubRunner.start(url)
        }

        val name = intent.getStringExtra(SkillDetailActivity.EXTRA_SKILL_NAME).orEmpty()
        binding.currentSkill.text = if (name.isBlank()) "Unknown skill" else name
    }

    override fun onDestroy() {
        githubRunner.cancel()
        githubRevalidationRunner.cancel()
        super.onDestroy()
    }

    private fun renderLocal() {
        val md = localSkillMd ?: run {
            clearResult()
            return
        }
        val candidate = runCatching {
            WorkspaceSkillUpdatePreview.candidateFromBytes(md, localSkillJson)
        }.getOrElse { error ->
            showBlocked(
                "Local candidate blocked",
                error.message ?: "Local candidate could not be inspected safely.",
            )
            return
        }
        renderCandidate(candidate, "Local candidate", githubCompletion = null)
    }

    private fun renderCandidate(
        candidate: WorkspaceSkillUpdatePreview.Candidate,
        sourceLabel: String,
        githubCompletion: WorkspaceSkillGitHubReadSession.Completion?,
    ) {
        val name = intent.getStringExtra(SkillDetailActivity.EXTRA_SKILL_NAME).orEmpty()
        val result = runCatching {
            val current = skillStore.load(name)
            WorkspaceSkillUpdatePreview.compare(current, candidate)
        }
        result.onFailure { error ->
            showBlocked(
                "Update preview unavailable",
                error.message ?: "Installed skill could not be re-opened and verified.",
            )
            return
        }
        val preview = result.getOrThrow()
        binding.resultCard.visibility = View.VISIBLE
        binding.resultRows.removeAllViews()
        binding.diffRows.removeAllViews()
        binding.applyUpdateButton.visibility = View.GONE
        pendingUpdate = null
        binding.resultSource.text = sourceLabel
        binding.resultStatus.text = preview.status.name.replace('_', ' ')
        binding.resultStatus.setTextColor(
            when (preview.status) {
                WorkspaceSkillUpdatePreview.Status.NON_WIDENING_PREVIEW ->
                    Color.rgb(108, 194, 145)
                WorkspaceSkillUpdatePreview.Status.IDENTICAL_PACKAGE ->
                    Color.rgb(145, 137, 145)
                else -> Color.rgb(255, 80, 110)
            }
        )
        binding.resultDetail.text = preview.statusDetail
        binding.activationImpact.text = preview.activationImpact

        listOf(
            "Current content" to preview.currentContentSha256,
            "Candidate content" to preview.candidateContentSha256,
            "Current package" to preview.currentPackageSha256,
            "Candidate package" to preview.candidatePackageSha256,
            "Current permissions" to preview.currentPermissionSha256,
            "Candidate permissions" to preview.candidatePermissionSha256,
            "Current provenance" to preview.currentProvenance,
            "Candidate provenance" to preview.candidateProvenance,
        ).forEach { (label, value) ->
            binding.resultRows.addView(row(label, value))
        }

        preview.setDeltas.forEach { delta ->
            binding.diffRows.addView(
                row(
                    delta.label,
                    "Added: " + values(delta.added) +
                        "\nRemoved: " + values(delta.removed) +
                        "\nUnchanged: " + values(delta.unchanged),
                    warning = delta.added.isNotEmpty(),
                )
            )
        }
        preview.scalarDeltas.forEach { delta ->
            binding.diffRows.addView(
                row(
                    delta.label + " · " + delta.classification,
                    delta.current + " → " + delta.candidate,
                    warning = delta.classification == "WIDENED",
                )
            )
        }

        if (preview.status == WorkspaceSkillUpdatePreview.Status.NON_WIDENING_PREVIEW) {
            val prepared = runCatching {
                val freshCurrent = skillStore.load(preview.currentName)
                WorkspaceSkillApprovedUpdate.prepare(freshCurrent, candidate)
            }.getOrElse { error ->
                binding.diffRows.addView(
                    row(
                        "BLOCKED",
                        error.message ?: "Update approval could not be prepared.",
                        warning = true,
                    )
                )
                return
            }

            pendingUpdate = if (githubCompletion == null) {
                PendingUpdate.Local(prepared)
            } else {
                val githubRevalidation = runCatching {
                    WorkspaceSkillGitHubInstallApproval.prepare(githubCompletion)
                }.getOrElse { error ->
                    binding.diffRows.addView(
                        row(
                            "BLOCKED",
                            error.message ?: "Pinned GitHub revalidation could not be prepared.",
                            warning = true,
                        )
                    )
                    return
                }
                PendingUpdate.GitHub(prepared, githubRevalidation)
            }
            binding.applyUpdateButton.visibility = View.VISIBLE
        }
    }

    private fun confirmUpdate(pending: PendingUpdate) {
        AlertDialog.Builder(this)
            .setTitle("Update " + pending.prepared.request.skillName + "?")
            .setMessage(pending.prepared.approvalSummary)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Update disabled") { _, _ ->
                binding.applyUpdateButton.visibility = View.GONE
                setMutationBusy(true)
                when (pending) {
                    is PendingUpdate.Local -> {
                        val md = localSkillMd
                        if (md == null) {
                            setMutationBusy(false)
                            showBlocked(
                                "Local candidate changed",
                                "SKILL.md is no longer selected. Preview again.",
                            )
                            return@setPositiveButton
                        }
                        val candidate = runCatching {
                            WorkspaceSkillUpdatePreview.candidateFromBytes(
                                md, localSkillJson)
                        }.getOrElse { error ->
                            setMutationBusy(false)
                            showBlocked(
                                "Local candidate changed",
                                error.message ?: "Local candidate no longer validates.",
                            )
                            return@setPositiveButton
                        }
                        applyApprovedUpdate(pending.prepared, candidate)
                    }
                    is PendingUpdate.GitHub -> {
                        githubRevalidationRunner.start(pending.revalidation)
                    }
                }
            }
            .show()
    }

    private fun applyApprovedUpdate(
        prepared: WorkspaceSkillApprovedUpdate.Prepared,
        candidate: WorkspaceSkillUpdatePreview.Candidate,
    ) {
        val outcome = runCatching {
            val current = skillStore.load(prepared.request.skillName)
            WorkspaceSkillApprovedUpdate.validate(prepared, current, candidate)
            skillStore.updateApprovedPackage(
                name = prepared.request.skillName,
                candidate = candidate,
                request = prepared.request,
                approvedToken = prepared.request.approvalToken,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        outcome.onSuccess { installed ->
            Toast.makeText(
                this,
                installed.entry.name + " updated · disabled",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }.onFailure { error ->
            setMutationBusy(false)
            Toast.makeText(
                this,
                error.message ?: "Skill update was not applied",
                Toast.LENGTH_LONG,
            ).show()
            pendingUpdate = null
            binding.applyUpdateButton.visibility = View.GONE
            binding.resultDetail.text =
                "Update approval is stale or no longer exact. Preview the candidate again."
        }
    }

    private fun setMutationBusy(busy: Boolean) {
        binding.chooseSkillMdButton.isEnabled = !busy
        binding.chooseSkillJsonButton.isEnabled = !busy
        binding.clearManifestButton.isEnabled = !busy
        binding.githubPreviewButton.isEnabled = !busy
        binding.githubUrl.isEnabled = !busy
        binding.applyUpdateButton.isEnabled = !busy
    }

    private fun showBlocked(title: String, message: String) {
        binding.resultCard.visibility = View.VISIBLE
        binding.resultRows.removeAllViews()
        binding.diffRows.removeAllViews()
        binding.applyUpdateButton.visibility = View.GONE
        pendingUpdate = null
        binding.resultSource.text = "Preview only"
        binding.resultStatus.text = title.uppercase()
        binding.resultStatus.setTextColor(Color.rgb(255, 80, 110))
        binding.resultDetail.text = message
        binding.activationImpact.text =
            "No update action was performed and the installed skill is unchanged."
    }

    private fun clearResult() {
        binding.resultCard.visibility = View.GONE
        binding.resultRows.removeAllViews()
        binding.diffRows.removeAllViews()
        binding.applyUpdateButton.visibility = View.GONE
        pendingUpdate = null
    }

    private fun setGithubBusy(busy: Boolean) {
        binding.githubPreviewButton.isEnabled = !busy
        binding.githubUrl.isEnabled = !busy
    }

    private fun selectFile(
        uri: Uri,
        expectedName: String,
        onSuccess: (ByteArray) -> Unit,
    ) {
        val result = runCatching {
            val actualName = displayName(uri)
            require(actualName == expectedName) {
                "Choose the exact " + expectedName + " file"
            }
            readBounded(uri)
        }
        result.onSuccess(onSuccess).onFailure { error ->
            Toast.makeText(
                this,
                error.message ?: "Selected file could not be inspected",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            .use { cursor ->
                require(cursor != null && cursor.moveToFirst()) {
                    "Selected document name is unavailable"
                }
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                require(index >= 0) { "Selected document name is unavailable" }
                return cursor.getString(index).orEmpty()
            }
    }

    private fun readBounded(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Selected document could not be opened")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= WorkspaceSkillImportPreview.MAX_FILE_BYTES) {
                    "Selected file exceeds the local skill update-preview bound"
                }
                output.write(buffer, 0, read)
            }
            require(total > 0) { "Selected file is empty" }
            return output.toByteArray()
        }
    }

    private fun values(values: List<String>): String =
        values.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None"

    private fun row(label: String, value: String, warning: Boolean = false): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(this@SkillUpdatePreviewActivity).apply {
                text = label.uppercase()
                setTextColor(
                    if (warning) Color.rgb(255, 180, 90)
                    else Color.rgb(108, 194, 145)
                )
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@SkillUpdatePreviewActivity).apply {
                text = value
                setTextColor(Color.rgb(224, 222, 224))
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
                setTextIsSelectable(true)
            })
        }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.githubUrl.windowToken, 0)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
