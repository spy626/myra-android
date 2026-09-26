package com.myra.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivitySkillDetailBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillCatalog
import com.myra.assistant.ui.workspace.WorkspaceSkillDisableApproval
import com.myra.assistant.ui.workspace.WorkspaceSkillReadOnlyDetail
import com.myra.assistant.ui.workspace.WorkspaceSkillStore
import java.io.File

/**
 * H2 read-only skill detail surface.
 *
 * The skill name from Intent is only a lookup key. The package is re-opened and integrity-checked
 * from the existing local skill store before any metadata is displayed.
 */
class SkillDetailActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SKILL_NAME = "skill_name"
    }

    private lateinit var binding: ActivitySkillDetailBinding
    private var pendingDisable: WorkspaceSkillDisableApproval.Prepared? = null
    private val skillStore by lazy {
        WorkspaceSkillStore(File(noBackupFilesDir, WorkspaceSkillStore.APP_DIRECTORY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.readinessButton.setOnClickListener {
            val name = intent.getStringExtra(EXTRA_SKILL_NAME).orEmpty()
            startActivity(Intent(this, SkillReadinessActivity::class.java)
                .putExtra(EXTRA_SKILL_NAME, name))
        }
        binding.disableButton.setOnClickListener { pendingDisable?.let(::confirmDisable) }
        binding.updatePreviewButton.setOnClickListener {
            val name = intent.getStringExtra(EXTRA_SKILL_NAME).orEmpty()
            startActivity(
                Intent(this, SkillUpdatePreviewActivity::class.java)
                    .putExtra(EXTRA_SKILL_NAME, name)
            )
        }
        binding.rollbackPreviewButton.setOnClickListener {
            val name = intent.getStringExtra(EXTRA_SKILL_NAME).orEmpty()
            startActivity(
                Intent(this, SkillRollbackPreviewActivity::class.java)
                    .putExtra(EXTRA_SKILL_NAME, name)
            )
        }
        binding.uninstallPreviewButton.setOnClickListener {
            val name = intent.getStringExtra(EXTRA_SKILL_NAME).orEmpty()
            startActivity(
                Intent(this, SkillUninstallPreviewActivity::class.java)
                    .putExtra(EXTRA_SKILL_NAME, name)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        renderDetail()
    }

    private fun renderDetail() {
        binding.detailList.removeAllViews()
        binding.readinessButton.visibility = View.GONE
        binding.disableButton.visibility = View.GONE
        binding.updatePreviewButton.visibility = View.GONE
        binding.rollbackPreviewButton.visibility = View.GONE
        binding.uninstallPreviewButton.visibility = View.GONE
        pendingDisable = null
        val name = intent.getStringExtra(EXTRA_SKILL_NAME).orEmpty()
        val loaded = runCatching {
            val installed = skillStore.load(name)
            installed to WorkspaceSkillReadOnlyDetail.from(installed)
        }.getOrElse {
            binding.skillName.text = "Skill unavailable"
            binding.skillState.text = "NOT VERIFIED"
            binding.skillState.setTextColor(Color.rgb(255, 80, 110))
            binding.skillDescription.text =
                "This installed skill could not be re-opened and verified. No skill action was performed."
            binding.detailList.visibility = View.GONE
            return
        }

        val (installed, detail) = loaded
        binding.updatePreviewButton.visibility = View.VISIBLE
        binding.uninstallPreviewButton.visibility = View.VISIBLE
        if (installed.entry.rollbackPoint != null) {
            binding.rollbackPreviewButton.visibility = View.VISIBLE
        }
        when (installed.entry.state) {
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED -> {
                binding.readinessButton.visibility = View.VISIBLE
            }
            WorkspaceSkillCatalog.State.ENABLED -> {
                pendingDisable = WorkspaceSkillDisableApproval.prepare(installed)
                binding.disableButton.visibility = View.VISIBLE
            }
        }

        binding.detailList.visibility = View.VISIBLE
        binding.skillName.text = detail.name
        binding.skillState.text = detail.stateLabel
        binding.skillState.setTextColor(
            if (detail.stateLabel == "ENABLED") Color.rgb(108, 194, 145)
            else Color.rgb(145, 137, 145)
        )
        binding.skillDescription.text = detail.description
        detail.rows.forEach { binding.detailList.addView(row(it)) }
    }

    private fun confirmDisable(prepared: WorkspaceSkillDisableApproval.Prepared) {
        AlertDialog.Builder(this)
            .setTitle("Disable " + prepared.skillName + "?")
            .setMessage(prepared.approvalSummary)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disable") { _, _ ->
                val outcome = runCatching {
                    skillStore.disable(
                        name = prepared.skillName,
                        request = prepared.request,
                        approvedToken = prepared.request.approvalToken,
                    )
                }
                outcome.onSuccess {
                    Toast.makeText(
                        this,
                        prepared.skillName + " disabled",
                        Toast.LENGTH_SHORT,
                    ).show()
                    renderDetail()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "Skill was not disabled",
                        Toast.LENGTH_LONG,
                    ).show()
                    renderDetail()
                }
            }
            .show()
    }

    private fun row(item: WorkspaceSkillReadOnlyDetail.Row): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(this@SkillDetailActivity).apply {
                text = item.label.uppercase()
                setTextColor(Color.rgb(108, 194, 145))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@SkillDetailActivity).apply {
                text = item.value
                setTextColor(Color.rgb(224, 222, 224))
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
                setTextIsSelectable(true)
            })
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
