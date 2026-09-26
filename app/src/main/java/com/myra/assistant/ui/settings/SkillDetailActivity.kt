package com.myra.assistant.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.databinding.ActivitySkillDetailBinding
import com.myra.assistant.ui.workspace.WorkspaceSkillApprovedUninstall
import com.myra.assistant.ui.workspace.WorkspaceSkillReadOnlyDetail
import com.myra.assistant.ui.workspace.WorkspaceSkillStore
import com.myra.assistant.ui.workspace.WorkspaceSkillUninstallDependencyGuard
import java.io.File

/** Simple skill details: name, description, status and guarded Delete. */
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
        binding.deleteButton.setOnClickListener { requestDelete() }
    }

    override fun onResume() {
        super.onResume()
        renderDetail()
    }

    private fun renderDetail() {
        val name = intent.getStringExtra(EXTRA_SKILL_NAME).orEmpty()
        val result = runCatching {
            WorkspaceSkillReadOnlyDetail.from(skillStore.load(name))
        }
        result.onFailure {
            binding.skillName.text = "Skill unavailable"
            binding.skillDescription.text = "This skill could not be verified."
            binding.skillState.text = "Unavailable"
            binding.skillState.setTextColor(Color.rgb(255, 80, 110))
            binding.deleteButton.isEnabled = false
            return
        }

        val detail = result.getOrThrow()
        binding.skillName.text = detail.name
        binding.skillDescription.text = detail.description
        binding.skillState.text = if (detail.stateLabel == "ENABLED") "Enabled" else "Disabled"
        binding.skillState.setTextColor(
            if (detail.stateLabel == "ENABLED") Color.rgb(108, 194, 145)
            else Color.rgb(145, 137, 145)
        )
        binding.deleteButton.isEnabled = true
    }

    private fun requestDelete() {
        val name = intent.getStringExtra(EXTRA_SKILL_NAME).orEmpty()
        val prepared = runCatching {
            val current = skillStore.load(name)
            val impact = WorkspaceSkillUninstallDependencyGuard.analyze(
                targetSkillName = name,
                installedSkills = skillStore.listVerified(),
            )
            require(!impact.blocked) {
                val kind = if (impact.dependents.size == 1) "skill" else "skills"
                "Delete dependent $kind first: " +
                    impact.dependents.joinToString(", ") { it.skillName }
            }
            val rollback = if (current.entry.rollbackPoint != null) skillStore.loadRollback(name) else null
            WorkspaceSkillApprovedUninstall.prepare(current, rollback)
        }.getOrElse { error ->
            AlertDialog.Builder(this)
                .setTitle("Can't delete this skill")
                .setMessage(error.message ?: "This skill cannot be deleted right now.")
                .setPositiveButton("Close", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete " + prepared.request.skillName + "?")
            .setMessage("Remove this skill from LYRA?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                binding.deleteButton.isEnabled = false
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
                        prepared.request.skillName + " deleted",
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        error.message ?: "Skill was not deleted",
                        Toast.LENGTH_LONG,
                    ).show()
                    renderDetail()
                }
            }
            .show()
    }
}
