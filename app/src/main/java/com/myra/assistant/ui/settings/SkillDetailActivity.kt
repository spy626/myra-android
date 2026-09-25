package com.myra.assistant.ui.settings

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivitySkillDetailBinding
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
    private val skillStore by lazy {
        WorkspaceSkillStore(File(noBackupFilesDir, WorkspaceSkillStore.APP_DIRECTORY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        renderDetail()
    }

    private fun renderDetail() {
        binding.detailList.removeAllViews()
        val name = intent.getStringExtra(EXTRA_SKILL_NAME).orEmpty()
        val detail = runCatching {
            WorkspaceSkillReadOnlyDetail.from(skillStore.load(name))
        }.getOrElse {
            binding.skillName.text = "Skill unavailable"
            binding.skillState.text = "NOT VERIFIED"
            binding.skillState.setTextColor(Color.rgb(255, 80, 110))
            binding.skillDescription.text =
                "This installed skill could not be re-opened and verified. No skill action was performed."
            binding.detailList.visibility = View.GONE
            return
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
