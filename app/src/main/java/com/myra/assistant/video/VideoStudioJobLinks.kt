package com.myra.assistant.video

/**
 * Read-only browser handoff for an authorized GitHub Actions run.
 *
 * Never accepts arbitrary URLs/domains, fetches private run metadata,
 * embeds a GitHub credential, or claims that a job has started/passed.
 * GitHub's signed-in website is the authoritative run-status viewer.
 */
internal object VideoStudioJobLinks {
    const val WORKFLOW_PAGE =
        "https://github.com/spy626/myra-android/actions/workflows/video-studio-slice-6.yml"

    private val validRunId = Regex("^[1-9][0-9]{0,17}$")

    fun statusPage(rawRunId: String): String {
        val runId = rawRunId.trim()
        require(validRunId.matches(runId)) {
            "Enter the numeric GitHub Actions run ID (not a URL)"
        }
        return "https://github.com/spy626/myra-android/actions/runs/" + runId
    }

    const val BOUNDARY_NOTICE =
        "Manual GitHub owner approval is required. This branch cannot enable " +
        "GitHub's Run workflow button until the workflow is installed on the " +
        "default branch. LYRA never submits jobs or reads private run status " +
        "automatically. No GitHub credentials are stored in the APK."
}
