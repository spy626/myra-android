package com.myra.assistant.video

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import android.widget.ArrayAdapter
import okhttp3.Call
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.databinding.ActivityVideoStudioBinding
import java.util.UUID

/**
 * Standalone opt-in storyboard UI and user-initiated public GitHub status GET.
 * No token, private reads, submission, storage writes, rendering, or changes
 * to existing Workspace/voice/memory flows.
 */
class VideoStudioActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoStudioBinding
    private lateinit var draftProjectId: String
    private var activeStatusCall: Call? = null

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
            binding.githubRunIdInput.setText(savedInstanceState.getString(STATE_RUN_ID, ""))
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

        binding.openGithubRunButton.setOnClickListener {
            val url = runCatching {
                VideoStudioJobLinks.statusPage(binding.githubRunIdInput.text.toString())
            }.getOrElse { error ->
                binding.storyboardText.text = "Invalid GitHub run ID: " +
                    (error.message ?: "Enter a numeric run ID")
                return@setOnClickListener
            }
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                binding.storyboardText.text =
                    "Could not open GitHub. No render job was submitted."
            }
        }

        binding.refreshGithubRunButton.setOnClickListener {
            val id = binding.githubRunIdInput.text.toString().trim()
            val valid = runCatching {
                VideoStudioJobStatusContract.publicApiUrl(id)
            }.isSuccess
            if (!valid) {
                binding.githubRunStatusText.text =
                    "Enter a valid numeric GitHub run ID to check a real status."
                return@setOnClickListener
            }
            activeStatusCall?.cancel()
            binding.githubRunStatusText.text = "Checking the public GitHub API once…"
            activeStatusCall = VideoStudioJobStatusReader.refresh(id) { result ->
                runOnUiThread {
                    if (isFinishing || isDestroyed ||
                        binding.githubRunIdInput.text.toString().trim() != id) {
                        return@runOnUiThread
                    }
                    binding.githubRunStatusText.text = result.fold(
                        onSuccess = { it.readable() },
                        onFailure = {
                            "Status unverified: " + (it.message ?: "Could not read GitHub") +
                                "\nUse Open GitHub run status for the authoritative page."
                        },
                    )
                }
            }
        }

        binding.openManualRenderWorkflowButton.setOnClickListener {
            val request = runCatching {
                val kind = when (binding.kindSpinner.selectedItemPosition) {
                    1 -> VideoStudioKind.CARTOON
                    2 -> VideoStudioKind.EXPLAINER
                    else -> VideoStudioKind.CONSTRUCTION
                }
                VideoStudioRenderRequestFactory.fromDraft(
                    VideoStudioPlanning.createDraft(
                        projectId = draftProjectId,
                        rawPrompt = binding.promptInput.text.toString(),
                        kind = kind,
                        durationSeconds = if (binding.durationGroup.checkedRadioButtonId ==
                            binding.duration60.id) 60 else 30,
                    ),
                )
            }.getOrElse { error ->
                binding.githubRunStatusText.text = "Cannot review manual render: " +
                    (error.message ?: "Unsupported mini-house request")
                return@setOnClickListener
            }
            val instructions = VideoStudioRenderRequestFactory.reviewText(request) +
                "\n\nProject ID: " + request.projectId +
                "\n\nGitHub owner must manually select Run workflow and enter " +
                "these reviewed settings AFTER a separately approved default-branch " +
                "deployment. This button only opens the website. It cannot submit " +
                "a render job or make a 30/60-second video."
            AlertDialog.Builder(this)
                .setTitle("Review GitHub manual render")
                .setMessage(instructions)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open GitHub") { _, _ ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse(VideoStudioJobLinks.WORKFLOW_PAGE)))
                    }.onFailure {
                        binding.githubRunStatusText.text =
                            "Could not open GitHub. No video job was submitted."
                    }
                }
                .show()
        }

        binding.reviewRenderRequestButton.setOnClickListener {
            val request = runCatching {
                val kind = when (binding.kindSpinner.selectedItemPosition) {
                    1 -> VideoStudioKind.CARTOON
                    2 -> VideoStudioKind.EXPLAINER
                    else -> VideoStudioKind.CONSTRUCTION
                }
                val draft = VideoStudioPlanning.createDraft(
                    projectId = draftProjectId,
                    rawPrompt = binding.promptInput.text.toString(),
                    kind = kind,
                    durationSeconds = if (binding.durationGroup.checkedRadioButtonId ==
                        binding.duration60.id) 60 else 30,
                )
                VideoStudioRenderRequestFactory.fromDraft(draft)
            }.getOrElse { error ->
                binding.storyboardText.text = "Cannot prepare 6s 3D test: " +
                    (error.message ?: "Unsupported mini-house brief")
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Review 6-second 3D test request")
                .setMessage(VideoStudioRenderRequestFactory.reviewText(request))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Share JSON") { _, _ ->
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, request.toJson())
                    }
                    runCatching {
                        startActivity(Intent.createChooser(share, "Share Blender test request"))
                    }.onFailure {
                        binding.storyboardText.text =
                            "No available app to share the JSON. No render was started."
                    }
                }
                .show()
        }
    }

    override fun onStop() {
        activeStatusCall?.cancel()
        activeStatusCall = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROJECT_ID, draftProjectId)
        outState.putString(STATE_PROMPT, binding.promptInput.text.toString())
        outState.putInt(STATE_KIND, binding.kindSpinner.selectedItemPosition)
        outState.putInt(STATE_SECONDS,
            if (binding.durationGroup.checkedRadioButtonId == binding.duration60.id) 60 else 30)
        outState.putString(STATE_PREVIEW, binding.storyboardText.text.toString())
        outState.putString(STATE_RUN_ID, binding.githubRunIdInput.text.toString())
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val STATE_PROJECT_ID = "video_studio_draft_project_id"
        private const val STATE_PROMPT = "video_studio_prompt"
        private const val STATE_KIND = "video_studio_kind"
        private const val STATE_SECONDS = "video_studio_duration"
        private const val STATE_PREVIEW = "video_studio_preview"
        private const val STATE_RUN_ID = "video_studio_github_run_id"
    }
}
