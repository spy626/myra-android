package com.myra.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.R
import com.myra.assistant.databinding.ActivitySkillManagerBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillReadOnlySummary
import com.myra.assistant.ui.workspace.WorkspaceSkillStore
import java.io.File

/** Simple installed-skills surface. Backend verification and lifecycle safety remain unchanged. */
class SkillManagerActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySkillManagerBinding
    private val skillStore by lazy {
        WorkspaceSkillStore(File(noBackupFilesDir, WorkspaceSkillStore.APP_DIRECTORY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        renderSkills()
    }

    private fun renderSkills() {
        binding.skillsList.removeAllViews()
        val items = runCatching {
            skillStore.listVerified().map(WorkspaceSkillReadOnlySummary::from)
        }.getOrElse {
            binding.catalogStatus.text = "Skills unavailable"
            binding.catalogStatus.setTextColor(Color.rgb(255, 80, 110))
            binding.emptyText.visibility = View.GONE
            return
        }

        binding.catalogStatus.setTextColor(Color.rgb(119, 112, 119))
        binding.catalogStatus.text = when (items.size) {
            0 -> "No skills added"
            1 -> "1 skill"
            else -> items.size.toString() + " skills"
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
                text = item.name
                setTextColor(Color.rgb(238, 238, 238))
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@SkillManagerActivity).apply {
                text = item.description
                setTextColor(Color.rgb(205, 201, 205))
                textSize = 13f
                setPadding(0, dp(5), 0, 0)
            })
            addView(TextView(this@SkillManagerActivity).apply {
                text = if (item.stateLabel == "ENABLED") "Enabled" else "Disabled"
                setTextColor(
                    if (item.stateLabel == "ENABLED") Color.rgb(108, 194, 145)
                    else Color.rgb(145, 137, 145)
                )
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.START
                setPadding(0, dp(8), 0, 0)
            })
            isClickable = true
            isFocusable = true
            contentDescription = "Open " + item.name
            setOnClickListener {
                startActivity(
                    Intent(this@SkillManagerActivity, SkillDetailActivity::class.java)
                        .putExtra(SkillDetailActivity.EXTRA_SKILL_NAME, item.name)
                )
            }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
