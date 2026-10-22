package com.myra.assistant.video

import org.json.JSONObject

/**
 * Slice 7: strict public GitHub Actions status proof for this repository's
 * exact, unchanged video-render workflow. No credentials, job submission,
 * automatic polling, or inference from an unrelated CI run.
 */
internal data class VideoStudioJobStatus(
    val runId: String,
    val state: String,
    val conclusion: String?,
    val revision: String,
    val trigger: String,
    val attempt: Int,
) {
    fun readable(): String = buildString {
        append("GitHub Video Studio run #").append(runId).append('\n')
        append("State: ").append(state).append('\n')
        if (conclusion != null) append("Result: ").append(conclusion).append('\n')
        append("Event: ").append(trigger).append('\n')
        append("Attempt: ").append(attempt).append('\n')
        append("Revision: ").append(revision.take(12)).append('\n')
        append('\n')
        if (state == "completed" && conclusion == "success") {
            append("GitHub reports success for this exact workflow. ")
            append("Video validation and receipt were CI steps, not an Android phone test.")
        } else if (state == "completed") {
            append("GitHub reported this run ended without a verified successful render.")
        } else {
            append("GitHub run is not completed. No playable video is claimed.")
        }
        append('\n').append("One-time public check; refresh manually for newer status.")
    }
}

internal object VideoStudioJobStatusContract {
    private const val REPO = "spy626/myra-android"
    private const val WORKFLOW = "Video Studio Slice 6 Authorized Render Job"
    private const val PATH = ".github/workflows/video-studio-slice-6.yml"
    private const val EXPERIMENT_BRANCH = "agent/lyra-video-studio-slice-6"
    private val SHA = Regex("^[0-9a-f]{40}$")
    private val STATES = setOf("queued", "in_progress", "completed", "waiting",
        "pending", "requested")
    private val CONCLUSIONS = setOf("success", "failure", "cancelled", "timed_out",
        "action_required", "neutral", "skipped", "stale", "startup_failure")

    fun publicApiUrl(rawRunId: String): String {
        val runId = rawRunId.trim()
        // Reuse the strict numeric-ID validator from Slice 6.
        VideoStudioJobLinks.statusPage(runId)
        return "https://api.github.com/repos/" + REPO + "/actions/runs/" + runId
    }

    fun parse(rawRunId: String, payload: String): VideoStudioJobStatus {
        val canonicalUrl = VideoStudioJobLinks.statusPage(rawRunId)
        val runId = rawRunId.trim()
        require(payload.length in 2..65536) { "GitHub response size is invalid" }
        val root = JSONObject(payload)
        require(root.optLong("id", 0) > 0L &&
            root.optLong("id", 0).toString() == runId) {
            "GitHub returned a different run ID"
        }
        require(root.optString("name") == WORKFLOW && root.optString("path") == PATH) {
            "This run does not belong to the LYRA Video Studio render workflow"
        }
        require(root.optJSONObject("repository")?.optString("full_name") == REPO &&
            root.optJSONObject("head_repository")?.optString("full_name") == REPO) {
            "GitHub run repository identity mismatch"
        }
        require(root.optString("html_url") == canonicalUrl) {
            "GitHub run URL identity mismatch"
        }
        require(root.optString("head_branch") in setOf(EXPERIMENT_BRANCH, "main")) {
            "Unsupported video-render branch"
        }
        val revision = root.optString("head_sha")
        require(SHA.matches(revision)) { "GitHub run revision is invalid" }
        val trigger = root.optString("event")
        require(trigger in setOf("workflow_dispatch", "push", "pull_request")) {
            "Unsupported video-render trigger"
        }
        val state = root.optString("status")
        require(state in STATES) { "Unknown GitHub run state" }
        val conclusion = if (root.isNull("conclusion")) null
            else root.optString("conclusion")
        require((state == "completed" && conclusion in CONCLUSIONS) ||
            (state != "completed" && conclusion == null)) {
            "Inconsistent GitHub job state"
        }
        val attempt = root.optInt("run_attempt", 0)
        require(attempt in 1..1000) { "GitHub run attempt invalid" }
        return VideoStudioJobStatus(runId, state, conclusion, revision, trigger, attempt)
    }
}
