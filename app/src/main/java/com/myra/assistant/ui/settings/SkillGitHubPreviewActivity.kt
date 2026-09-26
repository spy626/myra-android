package com.myra.assistant.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivitySkillGithubPreviewBinding
import com.myra.assistant.ui.workspace.WorkspaceAgentReachPolicy
import com.myra.assistant.ui.workspace.WorkspaceSkillCatalog
import com.myra.assistant.ui.workspace.WorkspaceSkillGitHubInstallApproval
import com.myra.assistant.ui.workspace.WorkspaceSkillGitHubInstallRunner
import com.myra.assistant.ui.workspace.WorkspaceSkillGitHubPreviewRunner
import com.myra.assistant.ui.workspace.WorkspaceSkillGitHubReadSession
import com.myra.assistant.ui.workspace.WorkspaceSkillImportPreview
import com.myra.assistant.ui.workspace.WorkspaceSkillStore
import java.io.File

/**
 * H8 pinned GitHub skill preview only.
 *
 * External repository text is displayed as untrusted skill metadata. This screen cannot install,
 * enable, invoke, execute or project the skill into a provider/model.
 */
class SkillGitHubPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillGithubPreviewBinding
    private lateinit var runner: WorkspaceSkillGitHubPreviewRunner
    private lateinit var installRunner: WorkspaceSkillGitHubInstallRunner
    private var pendingInstall: WorkspaceSkillGitHubInstallApproval.Prepared? = null
    private val skillStore by lazy {
        WorkspaceSkillStore(File(noBackupFilesDir, WorkspaceSkillStore.APP_DIRECTORY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillGithubPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        installRunner = WorkspaceSkillGitHubInstallRunner(
            object : WorkspaceSkillGitHubInstallRunner.Listener {
                override fun onStage(message: String) = runOnUiThread {
                    binding.progressText.text = message
                }

                override fun onValidated(
                    validated: WorkspaceSkillGitHubInstallApproval.Validated,
                ) = runOnUiThread {
                    val outcome = runCatching {
                        val fresh = validated.fresh
                        val installed = skillStore.installNew(
                            skill = fresh.skill,
                            packageFiles = fresh.packageFiles,
                            approval = fresh.approval,
                            approvedToken =
                                validated.prepared.installPrepared.approval.approvalToken,
                            installedAtMs = System.currentTimeMillis(),
                        )
                        require(
                            installed.entry.state ==
                                WorkspaceSkillCatalog.State.INSTALLED_DISABLED
                        ) { "Pinned GitHub skill unexpectedly gained activation authority" }
                        installed
                    }
                    outcome.onSuccess { installed ->
                        Toast.makeText(
                            this@SkillGitHubPreviewActivity,
                            installed.entry.name + " installed disabled",
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    }.onFailure { error ->
                        setBusy(false)
                        binding.installButton.visibility = View.VISIBLE
                        Toast.makeText(
                            this@SkillGitHubPreviewActivity,
                            error.message ?: "Pinned GitHub skill was not installed",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

                override fun onError(message: String) = runOnUiThread {
                    setBusy(false)
                    binding.installButton.visibility =
                        if (pendingInstall != null) View.VISIBLE else View.GONE
                    Toast.makeText(
                        this@SkillGitHubPreviewActivity,
                        message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        )

        runner = WorkspaceSkillGitHubPreviewRunner(
            object : WorkspaceSkillGitHubPreviewRunner.Listener {
                override fun onStage(message: String) = runOnUiThread {
                    binding.progressText.text = message
                    binding.progressText.setTextColor(Color.rgb(145, 137, 145))
                }

                override fun onComplete(
                    completion: WorkspaceSkillGitHubReadSession.Completion,
                ) = runOnUiThread {
                    setBusy(false)
                    render(completion)
                }

                override fun onError(message: String) = runOnUiThread {
                    setBusy(false)
                    binding.resultCard.visibility = View.VISIBLE
                    binding.previewStatus.text = "PREVIEW BLOCKED"
                    binding.previewStatus.setTextColor(Color.rgb(255, 80, 110))
                    binding.previewName.text = "GitHub skill unavailable"
                    binding.previewDescription.text = message
                    binding.previewRows.removeAllViews()
                    binding.warningRows.removeAllViews()
                }
            }
        )

        binding.backButton.setOnClickListener { finish() }
        binding.installButton.setOnClickListener {
            pendingInstall?.let(::confirmInstall)
        }
        binding.previewButton.setOnClickListener {
            val url = binding.githubUrl.text?.toString().orEmpty().trim()
            if (url.isBlank()) {
                binding.githubUrl.error = "Paste a GitHub repository or SKILL.md link"
                return@setOnClickListener
            }
            hideKeyboard()
            binding.resultCard.visibility = View.GONE
            binding.previewRows.removeAllViews()
            binding.warningRows.removeAllViews()
            binding.installButton.visibility = View.GONE
            pendingInstall = null
            setBusy(true)
            runner.start(url)
        }
    }

    override fun onDestroy() {
        runner.cancel()
        installRunner.cancel()
        super.onDestroy()
    }

    private fun setBusy(busy: Boolean) {
        binding.previewButton.isEnabled = !busy
        binding.githubUrl.isEnabled = !busy
    }

    private fun render(completion: WorkspaceSkillGitHubReadSession.Completion) {
        val preview = completion.preview
        binding.resultCard.visibility = View.VISIBLE
        binding.progressText.text =
            "Pinned " + completion.commitSha.take(12) +
                " · skill.json " + if (completion.manifestPresent) "present" else "not present"
        binding.previewName.text = preview.name
        binding.previewDescription.text = preview.description
        binding.previewStatus.text = when (preview.status) {
            WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW ->
                "PINNED PREVIEW READY"
            WorkspaceSkillImportPreview.Status.BLOCKED_SECRET ->
                "BLOCKED · POSSIBLE SECRET"
        }
        binding.previewStatus.setTextColor(
            if (preview.status == WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW)
                Color.rgb(108, 194, 145)
            else Color.rgb(255, 80, 110)
        )
        binding.previewRows.removeAllViews()
        binding.warningRows.removeAllViews()
        preview.rows.forEach { binding.previewRows.addView(row(it.label, it.value)) }
        completion.repositoryLicenseSpdx?.let {
            binding.previewRows.addView(row("Repository license", it))
        }
        preview.warnings.forEach {
            binding.warningRows.addView(row("WARNING", it, warning = true))
        }

        binding.installButton.visibility = View.GONE
        pendingInstall = null
        if (preview.status == WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW) {
            val prepared = runCatching {
                WorkspaceSkillGitHubInstallApproval.prepare(completion)
            }.getOrElse { error ->
                binding.warningRows.addView(
                    row(
                        "BLOCKED",
                        error.message ?: "Pinned install approval could not be prepared.",
                        warning = true,
                    )
                )
                return
            }
            val alreadyInstalled = runCatching {
                skillStore.listVerified().any { it.entry.name == prepared.skillName }
            }.getOrElse {
                binding.warningRows.addView(
                    row(
                        "BLOCKED",
                        "Installed skill catalog could not be verified; install remains unavailable.",
                        warning = true,
                    )
                )
                return
            }
            if (alreadyInstalled) {
                binding.warningRows.addView(
                    row(
                        "ALREADY INSTALLED",
                        "This skill name already exists. Use the separate update flow instead.",
                        warning = true,
                    )
                )
            } else {
                pendingInstall = prepared
                binding.installButton.visibility = View.VISIBLE
            }
        }
    }

    private fun confirmInstall(prepared: WorkspaceSkillGitHubInstallApproval.Prepared) {
        val currentUrlMatches = runCatching {
            WorkspaceAgentReachPolicy.parse(
                binding.githubUrl.text?.toString().orEmpty().trim()
            ).canonicalUrl == prepared.requestedCanonicalUrl
        }.getOrDefault(false)
        if (!currentUrlMatches) {
            Toast.makeText(
                this,
                "GitHub URL changed after preview. Preview it again before installing.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Install " + prepared.skillName + "?")
            .setMessage(prepared.approvalSummary)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install disabled") { _, _ ->
                setBusy(true)
                binding.installButton.visibility = View.GONE
                installRunner.start(prepared)
            }
            .show()
    }

    private fun row(label: String, value: String, warning: Boolean = false): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(this@SkillGitHubPreviewActivity).apply {
                text = label.uppercase()
                setTextColor(
                    if (warning) Color.rgb(255, 180, 90)
                    else Color.rgb(108, 194, 145)
                )
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@SkillGitHubPreviewActivity).apply {
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
