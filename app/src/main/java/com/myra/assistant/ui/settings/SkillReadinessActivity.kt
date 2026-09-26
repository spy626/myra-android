package com.myra.assistant.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivitySkillReadinessBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillCatalog
import com.myra.assistant.ui.workspace.WorkspaceSkillEnableApproval
import com.myra.assistant.ui.workspace.WorkspaceSkillEnablement
import com.myra.assistant.ui.workspace.WorkspaceSkillReadinessSurface
import com.myra.assistant.ui.workspace.WorkspaceSkillStore
import java.io.File

/**
 * H3 read-only readiness report.
 *
 * No approval token is accepted here and WorkspaceSkillStore.enable/disable/update are never called.
 * The report is computed fresh from the verified immutable package plus the current conservative
 * Phase-1 skill environment.
 */
class SkillReadinessActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillReadinessBinding
    private var pendingEnable: WorkspaceSkillEnableApproval.Prepared? = null
    private val skillStore by lazy {
        WorkspaceSkillStore(File(noBackupFilesDir, WorkspaceSkillStore.APP_DIRECTORY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillReadinessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.enableButton.setOnClickListener { pendingEnable?.let(::confirmEnable) }
    }

    override fun onResume() {
        super.onResume()
        renderReadiness()
    }

    private fun renderReadiness() {
        binding.checkList.removeAllViews()
        binding.enableButton.visibility = View.GONE
        pendingEnable = null
        val name = intent.getStringExtra(SkillDetailActivity.EXTRA_SKILL_NAME).orEmpty()
        val result = runCatching {
            val installed = skillStore.load(name)
            require(installed.entry.state == WorkspaceSkillCatalog.State.INSTALLED_DISABLED) {
                "Fresh readiness testing applies to installed-disabled skills. " +
                    "This skill is already enabled with a bound readiness report."
            }
            val allInstalled = skillStore.listVerified()
            val environment = WorkspaceSkillReadinessSurface.currentEnvironment(allInstalled)
            val report = WorkspaceSkillEnablement.test(
                installed = installed,
                environment = environment,
                testedAtMs = System.currentTimeMillis(),
            )
            Triple(installed, environment, report)
        }

        result.onFailure { error ->
            binding.skillName.text = if (name.isBlank()) "Skill unavailable" else name
            binding.reportStatus.text = "NOT TESTED"
            binding.reportStatus.setTextColor(Color.rgb(255, 80, 110))
            binding.reportSummary.text =
                error.message ?: "Readiness could not be evaluated. No skill action was performed."
            binding.reportMeta.visibility = View.GONE
            binding.checkList.visibility = View.GONE
            return
        }

        val (installed, environment, report) = result.getOrThrow()
        val view = WorkspaceSkillReadinessSurface.view(report)
        if (view.status == WorkspaceSkillEnablement.Status.PASS) {
            pendingEnable = WorkspaceSkillEnableApproval.prepare(installed, environment, report)
            binding.enableButton.visibility = View.VISIBLE
        }
        binding.checkList.visibility = View.VISIBLE
        binding.reportMeta.visibility = View.VISIBLE
        binding.skillName.text = installed.entry.name
        binding.reportStatus.text = view.status.name
        binding.reportStatus.setTextColor(
            if (view.status == WorkspaceSkillEnablement.Status.PASS)
                Color.rgb(108, 194, 145)
            else Color.rgb(255, 180, 90)
        )
        binding.reportSummary.text =
            if (view.status == WorkspaceSkillEnablement.Status.PASS)
                "Current readiness checks passed. This does not enable the skill."
            else
                "Current readiness is blocked. Missing gates stay unavailable until a later approved slice."
        binding.reportMeta.text =
            "environment " + view.environmentSha256.take(12) +
                " · report " + view.reportSha256.take(12)
        view.rows.forEach { binding.checkList.addView(checkRow(it)) }
    }

    private fun confirmEnable(prepared: WorkspaceSkillEnableApproval.Prepared) {
        AlertDialog.Builder(this)
            .setTitle("Enable " + prepared.skillName + "?")
            .setMessage(prepared.approvalSummary)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Enable") { _, _ ->
                val outcome = runCatching {
                    // Re-read the package and environment after the human tap. Any intervening
                    // catalog/package change makes the earlier approval request fail closed.
                    val freshInstalled = skillStore.load(prepared.skillName)
                    require(freshInstalled.entry.state ==
                        WorkspaceSkillCatalog.State.INSTALLED_DISABLED) {
                        "Skill state changed before enablement"
                    }
                    val freshEnvironment = WorkspaceSkillReadinessSurface.currentEnvironment(
                        skillStore.listVerified())
                    skillStore.enable(
                        name = prepared.skillName,
                        environment = freshEnvironment,
                        request = prepared.request,
                        approvedToken = prepared.request.approvalToken,
                        enabledAtMs = System.currentTimeMillis(),
                    )
                }
                outcome.onSuccess {
                    Toast.makeText(
                        this,
                        prepared.skillName + " enabled",
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "Skill was not enabled",
                        Toast.LENGTH_LONG,
                    ).show()
                    renderReadiness()
                }
            }
            .show()
    }

    private fun checkRow(row: WorkspaceSkillReadinessSurface.Row): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(this@SkillReadinessActivity).apply {
                text = (if (row.passed) "PASS · " else "BLOCKED · ") +
                    row.id.replace('-', ' ').uppercase()
                setTextColor(
                    if (row.passed) Color.rgb(108, 194, 145)
                    else Color.rgb(255, 180, 90)
                )
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@SkillReadinessActivity).apply {
                text = row.detail
                setTextColor(Color.rgb(214, 210, 214))
                textSize = 13f
                gravity = Gravity.START
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
