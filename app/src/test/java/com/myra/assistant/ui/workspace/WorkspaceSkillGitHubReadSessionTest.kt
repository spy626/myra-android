package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WorkspaceSkillGitHubReadSessionTest {
    private val sha = "1234567890abcdef1234567890abcdef12345678"

    private fun response(url: String, code: Int = 200, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody())
            .build()

    private fun meta(): String = JSONObject()
        .put("full_name", "a/b")
        .put("default_branch", "main")
        .put("html_url", "https://github.com/a/b")
        .put("archived", false)
        .put("fork", false)
        .put("license", JSONObject().put("spdx_id", "MIT"))
        .toString()

    private fun commit(): String = JSONObject().put("sha", sha).toString()

    private fun file(path: String, text: String): String = JSONObject()
        .put("type", "file")
        .put("encoding", "base64")
        .put("size", text.toByteArray().size)
        .put("content", Base64.getEncoder().encodeToString(text.toByteArray()))
        .put("html_url", "https://github.com/a/b/blob/" + sha + "/" + path)
        .toString()

    private fun skill(): String = """---
name: github-skill
description: Pinned GitHub skill.
license: MIT
---
## Verification
Require deterministic verification.
"""

    @Test fun repositoryRootPinsDefaultBranchThenReadsRootSkillAndOptionalManifest() {
        val start = WorkspaceSkillGitHubReadSession.start("https://github.com/a/b")
        assertEquals(
            WorkspaceSkillGitHubReadSession.Phase.AWAITING_METADATA,
            start.state.phase,
        )
        assertEquals("https://api.github.com/repos/a/b", start.request.url.toString())

        val afterMeta = WorkspaceSkillGitHubReadSession.acceptMetadata(
            start.state, response(start.request.url.toString(), body = meta()))
        val afterCommit = WorkspaceSkillGitHubReadSession.acceptCommit(
            afterMeta.state,
            response(afterMeta.request.url.toString(), body = commit()))
        assertTrue(afterCommit.request.url.toString()
            .contains("/contents/SKILL.md?ref=" + sha))

        val afterSkill = WorkspaceSkillGitHubReadSession.acceptSkill(
            afterCommit.state,
            response(afterCommit.request.url.toString(), body = file("SKILL.md", skill())),
            10L,
        )
        assertTrue(afterSkill.request.url.toString()
            .contains("/contents/skill.json?ref=" + sha))

        val done = WorkspaceSkillGitHubReadSession.acceptManifest(
            afterSkill.state,
            response(afterSkill.request.url.toString(), 404, "{}"),
            11L,
        )
        assertEquals(sha, done.commitSha)
        assertFalse(done.manifestPresent)
        assertEquals("MIT", done.repositoryLicenseSpdx)
        assertEquals(
            WorkspaceSkillImportPreview.Status.READY_FOR_INSTALL_REVIEW,
            done.preview.status,
        )
        assertEquals(
            "GitHub · pinned revision",
            done.preview.rows.first { it.label == "Origin" }.value,
        )
        assertEquals(
            sha,
            done.preview.rows.first { it.label == "Pinned revision" }.value,
        )
    }

    @Test fun directSkillBlobUsesSameDirectoryForManifestAtSamePinnedSha() {
        val start = WorkspaceSkillGitHubReadSession.start(
            "https://github.com/a/b/blob/main/skills/review/SKILL.md")
        assertEquals(
            WorkspaceSkillGitHubReadSession.Phase.AWAITING_COMMIT,
            start.state.phase,
        )
        val afterCommit = WorkspaceSkillGitHubReadSession.acceptCommit(
            start.state,
            response(start.request.url.toString(), body = commit()))
        assertTrue(afterCommit.request.url.toString()
            .contains("/contents/skills/review/SKILL.md?ref=" + sha))

        val afterSkill = WorkspaceSkillGitHubReadSession.acceptSkill(
            afterCommit.state,
            response(
                afterCommit.request.url.toString(),
                body = file("skills/review/SKILL.md", skill()),
            ),
            12L,
        )
        assertTrue(afterSkill.request.url.toString()
            .contains("/contents/skills/review/skill.json?ref=" + sha))

        val manifest = """{"networkDomains":["api.github.com"]}"""
        val done = WorkspaceSkillGitHubReadSession.acceptManifest(
            afterSkill.state,
            response(
                afterSkill.request.url.toString(),
                body = file("skills/review/skill.json", manifest),
            ),
            13L,
        )
        assertTrue(done.manifestPresent)
        assertEquals(
            "api.github.com",
            done.preview.rows.first { it.label == "Network domains" }.value,
        )
        assertTrue(done.sourceUrl.contains("/blob/" + sha + "/skills/review/SKILL.md"))
    }

    @Test fun nonSkillBlobAndUnsupportedGithubKindsAreRejected() {
        assertTrue(runCatching {
            WorkspaceSkillGitHubReadSession.start(
                "https://github.com/a/b/blob/main/README.md")
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceSkillGitHubReadSession.start(
                "https://github.com/a/b/issues/1")
        }.isFailure)
    }

    @Test fun githubSecretStillBlocksAfterPinnedRead() {
        val start = WorkspaceSkillGitHubReadSession.start(
            "https://github.com/a/b/blob/main/SKILL.md")
        val afterCommit = WorkspaceSkillGitHubReadSession.acceptCommit(
            start.state,
            response(start.request.url.toString(), body = commit()))
        val dangerous = skill() + "\napi_key = sk-abcdefghijklmnop"
        val afterSkill = WorkspaceSkillGitHubReadSession.acceptSkill(
            afterCommit.state,
            response(afterCommit.request.url.toString(), body = file("SKILL.md", dangerous)),
            20L,
        )
        val done = WorkspaceSkillGitHubReadSession.acceptManifest(
            afterSkill.state,
            response(afterSkill.request.url.toString(), 404, "{}"),
            21L,
        )
        assertEquals(
            WorkspaceSkillImportPreview.Status.BLOCKED_SECRET,
            done.preview.status,
        )
    }

    @Test fun genericPinnedFileRequestStaysPublicReadOnlyAndStrictUtf8() {
        val target = WorkspaceAgentReachPolicy.parse(
            "https://github.com/a/b/blob/main/SKILL.md")
        val selection = WorkspaceAgentReachGitHub.selection(target)
        val req = WorkspaceAgentReachGitHub.pinnedFileRequest(
            selection, sha, "SKILL.md")
        assertEquals("api.github.com", req.url.host)
        assertEquals("GET", req.method)
        assertTrue(req.header("Authorization") == null)

        val invalid = JSONObject()
            .put("type", "file")
            .put("encoding", "base64")
            .put("size", 2)
            .put("content", Base64.getEncoder().encodeToString(
                byteArrayOf(0xC3.toByte(), 0x28)))
            .put("html_url", "https://github.com/a/b/blob/" + sha + "/SKILL.md")
            .toString()
        assertTrue(runCatching {
            WorkspaceAgentReachGitHub.readPinnedFileContent(
                response(req.url.toString(), body = invalid),
                selection, sha, "SKILL.md", 1L)
        }.isFailure)
    }
}
