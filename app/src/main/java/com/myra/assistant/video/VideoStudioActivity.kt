package com.myra.assistant.video

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.databinding.ActivityVideoStudioBinding
import java.util.UUID

/**
 * Standalone opt-in storyboard UI. No AI requests, network, permissions,
 * storage writes, rendering, or changes to existing Workspace/voice flows.
 */
class VideoStudioActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoStudioBinding
    private lateinit var draftProjectId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoStudioBinding.inflate(layoutInflater)
        setContentView(binding.root)
        draftProjectId = savedInstanceState?.getString(STATE_PROJECT_ID)
            ?: UUID.randomUUID().toString()

        val choices = listOf("Miniature construction", "Original cartoon", "3D explainer")
        binding.kindSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, choices,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.backButton.setOnClickListener { finish() }
        if (savedInstanceState == null) {
            binding.promptInput.setText("Build a modern two-floor mini house with a garden")
            binding.durationGroup.check(binding.duration30.id)
        } else {
            binding.promptInput.setText(savedInstanceState.getString(STATE_PROMPT, ""))
            binding.kindSpinner.setSelection(savedInstanceState.getInt(STATE_KIND, 0))
            binding.durationGroup.check(
                if (savedInstanceState.getInt(STATE_SECONDS, 30) == 60)
                    binding.duration60.id else binding.duration30.id,
            )
            binding.storyboardText.text = savedInstanceState.getString(
                STATE_PREVIEW, binding.storyboardText.text.toString(),
            )
        }

        binding.previewButton.setOnClickListener {
            val kind = when (binding.kindSpinner.selectedItemPosition) {
                1 -> VideoStudioKind.CARTOON
                2 -> VideoStudioKind.EXPLAINER
                else -> VideoStudioKind.CONSTRUCTION
            }
            val duration = if (binding.durationGroup.checkedRadioButtonId ==
                binding.duration60.id) 60 else 30
            val result = runCatching {
                val draft = VideoStudioPlanning.createDraft(
                    projectId = draftProjectId,
                    rawPrompt = binding.promptInput.text.toString(),
                    kind = kind,
                    durationSeconds = duration,
                )
                VideoStudioPreviewFormatter.format(draft)
            }
            binding.storyboardText.text = result.getOrElse { error ->
                "Cannot preview storyboard: ${error.message ?: "Invalid brief"}"
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROJECT_ID, draftProjectId)
        outState.putString(STATE_PROMPT, binding.promptInput.text.toString())
        outState.putInt(STATE_KIND, binding.kindSpinner.selectedItemPosition)
        outState.putInt(STATE_SECONDS,
            if (binding.durationGroup.checkedRadioButtonId == binding.duration60.id) 60 else 30)
        outState.putString(STATE_PREVIEW, binding.storyboardText.text.toString())
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val STATE_PROJECT_ID = "video_studio_draft_project_id"
        private const val STATE_PROMPT = "video_studio_prompt"
        private const val STATE_KIND = "video_studio_kind"
        private const val STATE_SECONDS = "video_studio_duration"
        private const val STATE_PREVIEW = "video_studio_preview"
    }
}
