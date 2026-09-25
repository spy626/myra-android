package com.myra.assistant.ui.settings

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

/**
 * H1 read-only Skill Manager.
 *
 * This surface deliberately has no install, enable, disable, update, rollback or invocation action.
 * It only re-opens packages through WorkspaceSkillStore so corrupt/stale catalog state fails closed.
 */
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
