package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class WorkspaceSkillGitHubApprovedUpdateBridgeTest {
    @get:Rule val temp = TemporaryFolder()
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

    @Test fun h9PinnedRevalidationCanSatisfyOnlyTheExactH11ApprovedCandidate() {
        val store = WorkspaceSkillStore(temp.newFolder("github-update"))
        val oldMd = """---
name: review-code
description: Old.
---
## Verification
Check.
"""
        val old = WorkspaceSkillContract.parse(oldMd)
        val oldFiles = mapOf("SKILL.md" to oldMd.toByteArray())
        val oldSnapshot = WorkspaceSkillCatalog.snapshot(old, oldFiles)
        val oldApproval = WorkspaceSkillCatalog.approvalRequest(old, oldSnapshot)
        val current = store.install(
            old, oldFiles, oldApproval, oldApproval.approvalToken, 1L)

        val start = WorkspaceSkillGitHubReadSession.start(
            "https://github.com/a/b/blob/main/SKILL.md")
        val afterCommit = WorkspaceSkillGitHubReadSession.acceptCommit(
            start.state,
            response(
                start.request.url.toString(),
                body = JSONObject().put("sha", sha).toString(),
            ),
        )
        val nextMd = """---
name: review-code
description: Pinned newer.
---
## Verification
Check.
"""
        val afterSkill = WorkspaceSkillGitHubReadSession.acceptSkill(
            afterCommit.state,
            response(afterCommit.request.url.toString(), body = file("SKILL.md", nextMd)),
            2L,
        )
        val completion = WorkspaceSkillGitHubReadSession.acceptManifest(
            afterSkill.state,
            response(afterSkill.request.url.toString(), 404, "{}"),
            3L,
        )
        val candidate = requireNotNull(completion.updateCandidate)
        val updateApproval = WorkspaceSkillApprovedUpdate.prepare(current, candidate)
        val githubApproval = WorkspaceSkillGitHubInstallApproval.prepare(completion)

        val first = WorkspaceSkillGitHubInstallApproval.firstRequest(githubApproval)
        val next = WorkspaceSkillGitHubInstallApproval.acceptSkill(
            githubApproval,
            response(first.url.toString(), body = file("SKILL.md", nextMd)),
            4L,
        )
        val validated = WorkspaceSkillGitHubInstallApproval.acceptManifest(
            next.state,
            response(next.request.url.toString(), 404, "{}"),
            5L,
        )
        val revalidatedCandidate = WorkspaceSkillUpdatePreview.Candidate(
            skill = validated.fresh.skill,
            snapshot = validated.fresh.snapshot,
            permissionSha256 = validated.fresh.approval.permissionSha256,
            packageFiles = validated.fresh.packageFiles,
        )
        WorkspaceSkillApprovedUpdate.validate(
            updateApproval, store.load("review-code"), revalidatedCandidate)

        val updated = store.updateApprovedPackage(
            "review-code",
            revalidatedCandidate,
            updateApproval.request,
            updateApproval.request.approvalToken,
            6L,
        )
        assertEquals(WorkspaceSkillCatalog.State.INSTALLED_DISABLED, updated.entry.state)
        assertEquals(WorkspaceSkillContract.Origin.GITHUB_PINNED, updated.entry.provenance.origin)
        assertEquals(sha, updated.entry.provenance.pinnedRevision)
        assertTrue(updated.entry.packageSha256 != current.entry.packageSha256)
    }
}
