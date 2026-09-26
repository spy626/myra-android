package com.myra.assistant.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivitySkillUninstallPreviewBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillUninstallPreview
import com.myra.assistant.ui.workspace.WorkspaceSkillStore
import java.io.File

/**
 * H16 uninstall preview only. Current package and any recorded rollback package are freshly verified
 * before display. This screen has no uninstall confirmation and performs no mutation.
 */
class SkillUninstallPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillUninstallPreviewBinding

    private val skillStore by lazy {
        WorkspaceSkillStore(File(noBackupFilesDir, WorkspaceSkillStore.APP_DIRECTORY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillUninstallPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        render()
    }

    private fun render() {
        binding.impactRows.removeAllViews()
        val name = intent.getStringExtra(SkillDetailActivity.EXTRA_SKILL_NAME).orEmpty()
        val result = runCatching {
            val current = skillStore.load(name)
            val rollback = if (current.entry.rollbackPoint != null) {
                skillStore.loadRollback(name)
            } else null
            WorkspaceSkillUninstallPreview.from(current, rollback)
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

        val preview = result.getOrThrow()
        binding.status.text = preview.status
        binding.status.setTextColor(Color.rgb(255, 180, 90))
        binding.detail.text = preview.name + "\n" + preview.summary
        binding.safety.text =
            "PREVIEW ONLY · H16 does not uninstall, disable, delete package bytes, remove rollback " +
                "metadata or create an approval token."
        binding.impactRows.visibility = View.VISIBLE
        preview.rows.forEach { binding.impactRows.addView(row(it)) }
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
