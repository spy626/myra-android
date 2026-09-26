package com.myra.assistant.ui.settings

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
import com.myra.assistant.databinding.ActivitySkillRollbackPreviewBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillApprovedRollback
import com.myra.assistant.ui.workspace.WorkspaceSkillDisableDependencyGuard
import com.myra.assistant.ui.workspace.WorkspaceSkillRollbackPreview
import com.myra.assistant.ui.workspace.WorkspaceSkillStore
import java.io.File

/**
 * H12 rollback preview only. Both active and retained packages are reopened from the local immutable
 * store and verified before display. There is deliberately no rollback action on this screen.
 */
class SkillRollbackPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillRollbackPreviewBinding
    private var pendingRollback: WorkspaceSkillApprovedRollback.Prepared? = null

    private val skillStore by lazy {
        WorkspaceSkillStore(File(noBackupFilesDir, WorkspaceSkillStore.APP_DIRECTORY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillRollbackPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.rollbackButton.setOnClickListener {
            pendingRollback?.let(::confirmRollback)
        }
        render()
    }

    private fun render() {
        pendingRollback = null
        binding.rollbackButton.visibility = View.GONE
        val name = intent.getStringExtra(SkillDetailActivity.EXTRA_SKILL_NAME).orEmpty()
        val result = runCatching {
            val current = skillStore.load(name)
            val rollback = skillStore.loadRollback(name)
            val preview = WorkspaceSkillRollbackPreview.compare(current, rollback)
            val dependencyImpact = WorkspaceSkillDisableDependencyGuard.analyze(
                targetSkillName = name,
                installedSkills = skillStore.listVerified(),
            )
            val approval =
                if (dependencyImpact.blocked) null
                else WorkspaceSkillApprovedRollback.prepare(current, rollback)
            Triple(preview, dependencyImpact, approval)
        }
        result.onFailure { error ->
            binding.status.text = "ROLLBACK PREVIEW UNAVAILABLE"
            binding.status.setTextColor(Color.rgb(255, 80, 110))
            binding.detail.text =
                error.message ?: "The retained rollback package could not be verified."
            binding.activationImpact.text =
                "No rollback action was performed and the current installed skill is unchanged."
            binding.identityRows.removeAllViews()
            binding.permissionRows.removeAllViews()
            return
        }

        val (preview, dependencyImpact, approval) = result.getOrThrow()
        pendingRollback = approval
        binding.rollbackButton.visibility =
            if (approval == null) View.GONE else View.VISIBLE
        binding.status.text =
            if (dependencyImpact.blocked) "ROLLBACK BLOCKED · ENABLED DEPENDENTS"
            else preview.status.name.replace('_', ' ')
        binding.status.setTextColor(
            when {
                dependencyImpact.blocked -> Color.rgb(255, 80, 110)
                preview.status ==
                    WorkspaceSkillRollbackPreview.Status.RESTORES_BROADER_PERMISSIONS ->
                    Color.rgb(255, 180, 90)
                else -> Color.rgb(108, 194, 145)
            }
        )
        binding.detail.text =
            when {
                dependencyImpact.blocked ->
                    "Rollback would clear this dependency's activation authority while enabled " +
                        "skills still depend on it. Disable those dependents explicitly first."
                preview.status ==
                    WorkspaceSkillRollbackPreview.Status.RESTORES_BROADER_PERMISSIONS ->
                    "The retained version is verified, but rollback would restore at least one " +
                        "permission/invocation boundary removed by the current version."
                else ->
                    "The retained previous immutable version is verified. This is comparison only."
            }
        binding.activationImpact.text = preview.activationImpact

        binding.identityRows.removeAllViews()
        listOf(
            "Current description" to preview.currentDescription,
            "Rollback description" to preview.rollbackDescription,
            "Current content" to preview.currentContentSha256,
            "Rollback content" to preview.rollbackContentSha256,
            "Current package" to preview.currentPackageSha256,
            "Rollback package" to preview.rollbackPackageSha256,
            "Current permissions" to preview.currentPermissionSha256,
            "Rollback permissions" to preview.rollbackPermissionSha256,
            "Current provenance" to preview.currentProvenance,
            "Rollback provenance" to preview.rollbackProvenance,
            "Current installed at" to preview.currentInstalledAtMs.toString(),
            "Rollback installed at" to preview.rollbackInstalledAtMs.toString(),
        ).forEach { (label, value) ->
            binding.identityRows.addView(row(label, value))
        }

        binding.permissionRows.removeAllViews()
        preview.setDeltas.forEach { delta ->
            binding.permissionRows.addView(
                row(
                    delta.label,
                    "Restored: " + values(delta.restored) +
                        "\nRemoved: " + values(delta.removed) +
                        "\nUnchanged: " + values(delta.unchanged),
                    warning = delta.restored.isNotEmpty(),
                )
            )
        }
        preview.scalarDeltas.forEach { delta ->
            binding.permissionRows.addView(
                row(
                    delta.label + " · " + delta.classification,
                    delta.current + " → " + delta.rollback,
                    warning = delta.classification == "RESTORED",
                )
            )
        }
        if (dependencyImpact.blocked) {
            binding.permissionRows.addView(
                row(
                    "DEPENDENCY SAFETY",
                    dependencyImpact.enabledDependents.joinToString("\n") {
                        it.skillName + " · " + it.packageSha256.take(12)
                    },
                    warning = true,
                )
            )
        }
    }

    private fun confirmRollback(prepared: WorkspaceSkillApprovedRollback.Prepared) {
        AlertDialog.Builder(this)
            .setTitle("Rollback " + prepared.request.skillName + "?")
            .setMessage(prepared.approvalSummary)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rollback disabled") { _, _ ->
                binding.rollbackButton.isEnabled = false
                val outcome = runCatching {
                    skillStore.rollbackApproved(
                        name = prepared.request.skillName,
                        request = prepared.request,
                        approvedToken = prepared.request.approvalToken,
                        rolledBackAtMs = System.currentTimeMillis(),
                    )
                }
                outcome.onSuccess { installed ->
                    Toast.makeText(
                        this,
                        installed.entry.name + " rolled back · disabled",
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }.onFailure { error ->
                    binding.rollbackButton.isEnabled = true
                    Toast.makeText(
                        this,
                        error.message ?: "Skill rollback was not applied",
                        Toast.LENGTH_LONG,
                    ).show()
                    render()
                }
            }
            .show()
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

            addView(TextView(this@SkillRollbackPreviewActivity).apply {
                text = label.uppercase()
                setTextColor(
                    if (warning) Color.rgb(255, 180, 90)
                    else Color.rgb(108, 194, 145)
                )
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@SkillRollbackPreviewActivity).apply {
                text = value
                setTextColor(Color.rgb(224, 222, 224))
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
                setTextIsSelectable(true)
            })
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
