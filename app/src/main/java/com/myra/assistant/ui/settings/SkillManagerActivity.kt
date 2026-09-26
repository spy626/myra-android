package com.myra.assistant.ui.settings

import android.graphics.Color
import android.content.Intent
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
import com.myra.assistant.databinding.ActivitySkillManagerBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillReadOnlySummary
import com.myra.assistant.ui.workspace.WorkspaceSkillStore
import java.io.File

/**
 * Skill Manager.
 *
 * Installed-skill actions stay on their separately approval-bound flows. H15 adds only a bounded
 * storage audit plus explicit cleanup of exact catalog-unreferenced immutable package directories.
 */
class SkillManagerActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillManagerBinding
    private var pendingRetentionAudit: WorkspaceSkillStore.RetentionAudit? = null
    private val skillStore by lazy {
        WorkspaceSkillStore(File(noBackupFilesDir, WorkspaceSkillStore.APP_DIRECTORY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.previewImportButton.setOnClickListener {
            startActivity(Intent(this, SkillImportPreviewActivity::class.java))
        }
        binding.previewGithubButton.setOnClickListener {
            startActivity(Intent(this, SkillGitHubPreviewActivity::class.java))
        }
        binding.auditStorageButton.setOnClickListener { renderRetentionAudit() }
        binding.cleanupStorageButton.setOnClickListener {
            pendingRetentionAudit?.let(::confirmStorageCleanup)
        }
    }

    override fun onResume() {
        super.onResume()
        renderSkills()
        renderRetentionAudit()
    }

    private fun renderRetentionAudit() {
        pendingRetentionAudit = null
        binding.cleanupStorageButton.visibility = View.GONE
        val audit = runCatching { skillStore.retentionAudit() }.getOrElse { error ->
            binding.storageStatus.setTextColor(Color.rgb(255, 80, 110))
            binding.storageStatus.text =
                error.message ?: "Skill package storage could not be audited."
            return
        }

        pendingRetentionAudit = audit
        val reclaimable = audit.reclaimablePackageSha256.size
        binding.storageStatus.setTextColor(
            if (reclaimable > 0) Color.rgb(255, 180, 90)
            else Color.rgb(108, 194, 145)
        )
        binding.storageStatus.text = buildString {
            append("Protected current packages: ")
            append(audit.currentPackageSha256.size)
            append("\nProtected rollback packages: ")
            append(audit.rollbackPackageSha256.size)
            append("\nReclaimable unreferenced packages: ")
            append(reclaimable)
            if (audit.ignoredEntryCount > 0) {
                append("\nIgnored non-package entries: ")
                append(audit.ignoredEntryCount)
            }
        }
        binding.cleanupStorageButton.visibility =
            if (reclaimable > 0) View.VISIBLE else View.GONE
    }

    private fun confirmStorageCleanup(audit: WorkspaceSkillStore.RetentionAudit) {
        val targets = audit.reclaimablePackageSha256
        if (targets.isEmpty()) {
            renderRetentionAudit()
            return
        }
        val preview = targets.take(8).joinToString("\n") { "• " + it.take(12) }
        val extra = (targets.size - 8).coerceAtLeast(0)
        val message = buildString {
            append("Delete only the exact unreferenced immutable package directories from this audit.")
            append("\n\nCurrent and rollback packages stay protected.")
            append("\n\nReclaimable packages:\n")
            append(preview)
            if (extra > 0) append("\n• +").append(extra).append(" more")
            append("\n\nIf storage changes before confirmation, cleanup will fail closed.")
        }

        AlertDialog.Builder(this)
            .setTitle("Clean " + targets.size + " unreferenced package" +
                if (targets.size == 1) "?" else "s?")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clean packages") { _, _ ->
                binding.cleanupStorageButton.isEnabled = false
                val outcome = runCatching { skillStore.cleanupAuditedPackages(audit) }
                outcome.onSuccess { deleted ->
                    Toast.makeText(
                        this,
                        deleted.size.toString() + " unreferenced package" +
                            if (deleted.size == 1) " cleaned" else "s cleaned",
                        Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "Skill storage cleanup was not applied",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                binding.cleanupStorageButton.isEnabled = true
                renderRetentionAudit()
            }
            .show()
    }

    private fun renderSkills() {
        binding.skillsList.removeAllViews()
        val verified = runCatching { skillStore.listVerified() }.getOrElse {
            binding.catalogStatus.text =
                "Skill catalog could not be verified. No skill action was performed."
            binding.catalogStatus.setTextColor(Color.rgb(255, 80, 110))
            binding.emptyText.visibility = View.GONE
            return
        }
        val items = runCatching {
            verified.map(WorkspaceSkillReadOnlySummary::from)
        }.getOrElse {
            binding.catalogStatus.text =
                "Installed skill metadata could not be verified. No skill action was performed."
            binding.catalogStatus.setTextColor(Color.rgb(255, 80, 110))
            binding.emptyText.visibility = View.GONE
            return
        }

        binding.catalogStatus.setTextColor(Color.rgb(119, 112, 119))
        binding.catalogStatus.text = when (items.size) {
            0 -> "No installed skill packages"
            1 -> "1 verified installed skill package"
            else -> items.size.toString() + " verified installed skill packages"
        }
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { binding.skillsList.addView(skillCard(it)) }
    }

    private fun skillCard(item: WorkspaceSkillReadOnlySummary.Item): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }

            addView(TextView(this@SkillManagerActivity).apply {
                text = item.stateLabel
                setTextColor(
                    if (item.stateLabel == "ENABLED") Color.rgb(108, 194, 145)
                    else Color.rgb(145, 137, 145)
                )
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@SkillManagerActivity).apply {
                text = item.name
                setTextColor(Color.rgb(238, 238, 238))
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(this@SkillManagerActivity).apply {
                text = item.description
                setTextColor(Color.rgb(205, 201, 205))
                textSize = 13f
                setPadding(0, dp(5), 0, 0)
            })
            addView(detail(item.originLabel))
            addView(detail(item.permissionSummary))
            addView(detail(item.accessSummary))
            addView(detail(item.activationSummary))
            addView(detail(item.identitySummary))
            isClickable = true
            isFocusable = true
            contentDescription = "Open read-only details for " + item.name
            setOnClickListener {
                startActivity(Intent(this@SkillManagerActivity, SkillDetailActivity::class.java)
                    .putExtra(SkillDetailActivity.EXTRA_SKILL_NAME, item.name))
            }
        }

    private fun detail(value: String) = TextView(this).apply {
        text = value
        setTextColor(Color.rgb(119, 112, 119))
        textSize = 11f
        gravity = Gravity.START
        setPadding(0, dp(6), 0, 0)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
