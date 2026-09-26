package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WorkspaceSkillGitHubUpdateCandidateTest {
    private val sha = "1234567890abcdef1234567890abcdef12345678"

    private fun response(url: String, code: Int = 200, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody())
            .build()

    private fun file(path: String, text: String): String = JSONObject()
        .put("type", "file")
        .put("encoding", "base64")
        .put("size", text.toByteArray().size)
        .put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
        .put("html_url", "https://github.com/a/b/blob/" + sha + "/" + path)
        .toString()

    @Test fun h8CompletionCarriesTypedPinnedCandidateForReadOnlyUpdatePreview() {
        val start = WorkspaceSkillGitHubReadSession.start(
            "https://github.com/a/b/blob/main/SKILL.md")
        val afterCommit = WorkspaceSkillGitHubReadSession.acceptCommit(
            start.state,
            response(start.request.url.toString(), body = JSONObject().put("sha", sha).toString()),
        )
        val skill = """---
name: review-code
description: Pinned update candidate.
---
## Verification
Check.
"""
        val afterSkill = WorkspaceSkillGitHubReadSession.acceptSkill(
            afterCommit.state,
            response(afterCommit.request.url.toString(), body = file("SKILL.md", skill)),
            1L,
        )
        val done = WorkspaceSkillGitHubReadSession.acceptManifest(
            afterSkill.state,
            response(afterSkill.request.url.toString(), 404, "{}"),
            2L,
        )

        val candidate = requireNotNull(done.updateCandidate)
        assertEquals(WorkspaceSkillContract.Origin.GITHUB_PINNED, candidate.skill.provenance.origin)
        assertEquals(sha, candidate.skill.provenance.pinnedRevision)
        assertEquals(done.preview.name, candidate.skill.name)
        assertTrue(candidate.snapshot.packageSha256.length == 64)
    }
}
