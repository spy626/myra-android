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
import com.myra.assistant.databinding.ActivitySkillUninstallPreviewBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillApprovedUninstall
import com.myra.assistant.ui.workspace.WorkspaceSkillUninstallPreview
import com.myra.assistant.ui.workspace.WorkspaceSkillStore
import java.io.File

/**
 * H16 preview plus H17 exact human-approved uninstall.
 *
 * Preview remains read-only. H17 only exposes a local confirmation prepared from freshly verified
 * current/rollback identities; the store revalidates them again before catalog removal.
 */
class SkillUninstallPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillUninstallPreviewBinding
    private var pendingUninstall: WorkspaceSkillApprovedUninstall.Prepared? = null

    private val skillStore by lazy {
        WorkspaceSkillStore(File(noBackupFilesDir, WorkspaceSkillStore.APP_DIRECTORY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillUninstallPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.uninstallButton.setOnClickListener {
            pendingUninstall?.let(::confirmUninstall)
        }
        render()
    }

    private fun render() {
        binding.impactRows.removeAllViews()
        binding.uninstallButton.visibility = View.GONE
        pendingUninstall = null
        val name = intent.getStringExtra(SkillDetailActivity.EXTRA_SKILL_NAME).orEmpty()
        val result = runCatching {
            val current = skillStore.load(name)
            val rollback = if (current.entry.rollbackPoint != null) {
                skillStore.loadRollback(name)
            } else null
            val preview = WorkspaceSkillUninstallPreview.from(current, rollback)
            preview to WorkspaceSkillApprovedUninstall.prepare(current, rollback)
        }
        result.onFailure { error ->
            binding.status.text = "UNINSTALL PREVIEW UNAVAILABLE"
            binding.status.setTextColor(Color.rgb(255, 80, 110))
            binding.detail.text =
                error.message ?: "The installed skill could not be freshly verified."
            binding.safety.text =
                "No uninstall approval was created and no catalog/package mutation was performed."
            binding.impactRows.visibility = View.GONE
            return
        }

        val (preview, prepared) = result.getOrThrow()
        pendingUninstall = prepared
        binding.status.text = preview.status
        binding.status.setTextColor(Color.rgb(255, 180, 90))
        binding.detail.text = preview.name + "\n" + preview.summary
        binding.safety.text =
            "H17 APPROVAL · Uninstall requires a separate confirmation bound to this exact verified " +
                "current state and rollback identity. Package bytes are never deleted here."
        binding.uninstallButton.visibility = View.VISIBLE
        binding.impactRows.visibility = View.VISIBLE
        preview.rows.forEach { binding.impactRows.addView(row(it)) }
    }

    private fun confirmUninstall(prepared: WorkspaceSkillApprovedUninstall.Prepared) {
        AlertDialog.Builder(this)
            .setTitle("Uninstall " + prepared.request.skillName + "?")
            .setMessage(prepared.approvalSummary)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Uninstall") { _, _ ->
                binding.uninstallButton.isEnabled = false
                val outcome = runCatching {
                    skillStore.uninstallApproved(
                        name = prepared.request.skillName,
                        request = prepared.request,
                        approvedToken = prepared.request.approvalToken,
                    )
                }
                outcome.onSuccess {
                    Toast.makeText(
                        this,
                        prepared.request.skillName + " uninstalled",
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "Skill was not uninstalled",
                        Toast.LENGTH_LONG,
                    ).show()
                    binding.uninstallButton.isEnabled = true
                    render()
                }
            }
            .show()
    }

    private fun row(item: WorkspaceSkillUninstallPreview.Row): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(this@SkillUninstallPreviewActivity).apply {
                text = item.label.uppercase()
                setTextColor(Color.rgb(255, 180, 90))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@SkillUninstallPreviewActivity).apply {
                text = item.value
                setTextColor(Color.rgb(224, 222, 224))
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
                setTextIsSelectable(true)
            })
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
