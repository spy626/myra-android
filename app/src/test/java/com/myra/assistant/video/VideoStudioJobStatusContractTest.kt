package com.myra.assistant.video

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStudioJobStatusContractTest {
    private val runId = "39861000838"

    private fun fixture(
        name: String = "Video Studio Slice 6 Authorized Render Job",
        path: String = ".github/workflows/video-studio-slice-6.yml",
        state: String = "completed",
        conclusion: String? = "success",
        branch: String = "agent/lyra-video-studio-slice-6",
    ): JSONObject = JSONObject()
        .put("id", 39861000838L)
        .put("name", name)
        .put("path", path)
        .put("status", state)
        .put("conclusion", conclusion ?: JSONObject.NULL)
        .put("repository", JSONObject().put("full_name", "spy626/myra-android"))
        .put("head_repository", JSONObject().put("full_name", "spy626/myra-android"))
        .put("html_url", VideoStudioJobLinks.statusPage(runId))
        .put("head_branch", branch)
        .put("head_sha", "5bcec59d558f905e396787882900b3bf83a03713")
        .put("event", "push")
        .put("run_attempt", 1)

    @Test fun verifiedCompletedRunIsReadOnlyAndSourceBound() {
        val result = VideoStudioJobStatusContract.parse(runId, fixture().toString())
        assertEquals("completed", result.state)
        assertEquals("success", result.conclusion)
        assertTrue(result.readable().contains("GitHub reports success"))
        assertTrue(result.readable().contains("not an Android phone test"))
        assertEquals("https://api.github.com/repos/spy626/myra-android/actions/runs/" + runId,
            VideoStudioJobStatusContract.publicApiUrl(runId))
    }

    @Test fun liveRunDoesNotPretendToHaveVideo() {
        val queued = VideoStudioJobStatusContract.parse(runId,
            fixture(state = "in_progress", conclusion = null).toString())
        assertTrue(queued.readable().contains("No playable video is claimed"))
        assertFalse(queued.readable().contains("GitHub reports success"))
        val failed = VideoStudioJobStatusContract.parse(runId,
            fixture(state = "completed", conclusion = "cancelled").toString())
        assertTrue(failed.readable().contains("without a verified successful render"))
    }

    @Test fun rejectAnyUnrelatedOrSpoofedRunIdentity() {
        val cases = listOf<(JSONObject) -> Unit>(
            { it.put("id", 39861000839L) },
            { it.put("name", "Build Android APK") },
            { it.put("path", ".github/workflows/other.yml") },
            { it.getJSONObject("repository").put("full_name", "other/repo") },
            { it.getJSONObject("head_repository").put("full_name", "other/repo") },
            { it.put("html_url", "https://attacker.invalid/actions/runs/" + runId) },
            { it.put("head_branch", "agent/untrusted") },
            { it.put("head_sha", "invalid") },
            { it.put("event", "issue_comment") },
            { it.put("run_attempt", 0) },
            { it.put("status", "unknown") },
            { it.put("conclusion", "undefined") },
        )
        cases.forEachIndexed { i, change ->
            val data = fixture()
            change(data)
            assertTrue("invalid fixture #" + i, runCatching {
                VideoStudioJobStatusContract.parse(runId, data.toString())
            }.isFailure)
        }
    }

    @Test fun rejectStateContradictionsAndUnsafeUrls() {
        assertTrue(runCatching {
            VideoStudioJobStatusContract.parse(runId,
                fixture(state = "in_progress", conclusion = "success").toString())
        }.isFailure)
        assertTrue(runCatching {
            VideoStudioJobStatusContract.parse(runId,
                fixture(state = "completed", conclusion = null).toString())
        }.isFailure)
        for (unsafe in listOf("0", "http://other.invalid", "1/../../evil", "123a")) {
            assertTrue(runCatching {
                VideoStudioJobStatusContract.publicApiUrl(unsafe)
            }.isFailure)
        }
        assertTrue(runCatching {
            VideoStudioJobStatusContract.parse(runId, "x".repeat(65537))
        }.isFailure)
    }
}
