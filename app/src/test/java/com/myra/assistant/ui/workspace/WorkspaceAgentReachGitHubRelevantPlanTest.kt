package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentReachGitHubRelevantPlanTest {
    private fun response(url: String, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("test")
            .body(body.toResponseBody())
            .build()

    private fun tree(sha: String): String = JSONObject()
        .put("sha", sha)
        .put("truncated", false)
        .put("tree", JSONArray()
            .put(JSONObject().put("path", "README.md").put("type", "blob")
                .put("sha", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("size", 1_000))
            .put(JSONObject().put("path", "SKILL.md").put("type", "blob")
                .put("sha", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").put("size", 3_000))
            .put(JSONObject().put("path", "src/browser/security.py").put("type", "blob")
                .put("sha", "cccccccccccccccccccccccccccccccccccccccc").put("size", 20_000))
            .put(JSONObject().put("path", "docs/browser.md").put("type", "blob")
                .put("sha", "dddddddddddddddddddddddddddddddddddddddd").put("size", 18_000))
            .put(JSONObject().put("path", "src/browser/engine.py").put("type", "blob")
                .put("sha", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee").put("size", 40_000))
            .put(JSONObject().put("path", "src/browser/extra.py").put("type", "blob")
                .put("sha", "ffffffffffffffffffffffffffffffffffffffff").put("size", 40_000)))
        .toString()

    @Test fun planUsesPinnedShaAndOnlyBoundedRelevantFiles() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val mapRequest = WorkspaceAgentReachGitHubRelevantPlan.pathMapRequest(target, sha)
        val plan = WorkspaceAgentReachGitHubRelevantPlan.build(
            target = target,
            commitSha = sha,
            userRequest = "check browser security and skills",
            response = response(mapRequest.url.toString(), tree(sha)),
        )

        assertEquals(sha, plan.commitSha)
        assertEquals(sha, plan.repoIndex.commitSha)
        assertEquals(
            WorkspaceAgentReachGitHubRepoIndex.Category.SKILLS_PLUGINS,
            plan.repoIndex.entries.first { it.path == "SKILL.md" }.category)
        assertTrue(plan.files.isNotEmpty())
        assertTrue(plan.files.size <= 4)
        assertEquals("SKILL.md", plan.files.first().candidate.path)
        assertFalse(plan.files.any { it.candidate.path == "README.md" })
        plan.files.forEach {
            assertEquals("api.github.com", it.request.url.host)
            assertTrue(it.request.url.toString().contains("ref=$sha"))
            assertNull(it.request.header("Authorization"))
        }
        val declared = plan.files.sumOf { it.candidate.size ?: 24_000 }
        assertTrue(declared <= 96_000)
    }

    @Test fun noRelevantFilesProducesEmptyPlanNotWholeRepoFallback() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val mapRequest = WorkspaceAgentReachGitHubRelevantPlan.pathMapRequest(target, sha)
        val body = JSONObject()
            .put("sha", sha)
            .put("truncated", false)
            .put("tree", JSONArray()
                .put(JSONObject().put("path", "image.png").put("type", "blob")
                    .put("sha", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("size", 1000)))
            .toString()
        val plan = WorkspaceAgentReachGitHubRelevantPlan.build(
            target, sha, "check browser ideas",
            response(mapRequest.url.toString(), body))
        assertTrue(plan.files.isEmpty())
    }

    @Test fun fileRequestsRemainPinnedAndPathEncoded() {
        val target = WorkspaceAgentReachPolicy.parse("https://github.com/a/b")
        val selection = WorkspaceAgentReachGitHub.selection(target)
        val sha = "1234567890abcdef1234567890abcdef12345678"
        val request = WorkspaceAgentReachGitHub.pinnedRepositoryFileRequest(
            selection, sha, "docs/My File.md")
        assertEquals(
            "https://api.github.com/repos/a/b/contents/docs/My%20File.md?ref=$sha",
            request.url.toString())
    }
}
