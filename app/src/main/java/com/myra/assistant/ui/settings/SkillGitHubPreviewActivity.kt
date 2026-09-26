package com.myra.assistant.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivitySkillGithubPreviewBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillGitHubPreviewRunner
import com.myra.assistant.ui.workspace.WorkspaceSkillGitHubReadSession
import com.myra.assistant.ui.workspace.WorkspaceSkillImportPreview

/**
 * H8 pinned GitHub skill preview only.
 *
 * External repository text is displayed as untrusted skill metadata. This screen cannot install,
 * enable, invoke, execute or project the skill into a provider/model.
 */
class SkillGitHubPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillGithubPreviewBinding
    private lateinit var runner: WorkspaceSkillGitHubPreviewRunner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillGithubPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            setBusy(true)
            runner.start(url)
        }
    }

    override fun onDestroy() {
        runner.cancel()
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
