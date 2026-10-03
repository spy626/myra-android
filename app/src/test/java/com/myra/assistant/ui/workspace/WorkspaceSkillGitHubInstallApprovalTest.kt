package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class WorkspaceSkillGitHubInstallApprovalTest {
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

    private fun skill(): String = """---
name: github-install
description: Install pinned GitHub skill.
---
## Verification
Require deterministic verification.
"""

    private fun completion(manifest: String? = null): WorkspaceSkillGitHubReadSession.Completion {
        val target = WorkspaceAgentReachPolicy.parse(
            "https://github.com/a/b/blob/main/SKILL.md")
        val selection = WorkspaceAgentReachGitHub.selection(target)
        val source = "https://github.com/a/b/blob/" + sha + "/SKILL.md"
        val provenance = WorkspaceSkillContract.Provenance(
            origin = WorkspaceSkillContract.Origin.GITHUB_PINNED,
            sourceUrl = source,
            pinnedRevision = sha,
        )
        val mdBytes = skill().toByteArray()
        val jsonBytes = manifest?.toByteArray()
        val preview = WorkspaceSkillImportPreview.inspect(
            mdBytes, jsonBytes, provenance)
        val install = WorkspaceSkillInstallApproval.prepare(
            mdBytes, jsonBytes, provenance)
        return WorkspaceSkillGitHubReadSession.Completion(
            preview = preview,
            commitSha = sha,
            sourceUrl = source,
            manifestPresent = manifest != null,
            repositoryLicenseSpdx = null,
            selection = selection,
            skillPath = "SKILL.md",
            manifestPath = "skill.json",
            installPrepared = install,
        )
    }

    @Test fun preparedApprovalShowsPinnedIdentityButNeverApprovalToken() {
        val prepared = WorkspaceSkillGitHubInstallApproval.prepare(completion())

        assertEquals("github-install", prepared.skillName)
        assertEquals(sha, prepared.commitSha)
        assertTrue(prepared.approvalSummary.contains("pinned GitHub skill"))
        assertTrue(prepared.approvalSummary.contains("Pinned revision: " + sha))
        assertTrue(prepared.approvalSummary.contains(prepared.sourceUrl))
        assertFalse(
            prepared.approvalSummary.contains(
                prepared.installPrepared.approval.approvalToken)
        )
    }

    @Test fun samePinnedBytesRevalidateAndInstallDisabledWithGitHubProvenance() {
        val prepared = WorkspaceSkillGitHubInstallApproval.prepare(completion())
        val first = WorkspaceSkillGitHubInstallApproval.firstRequest(prepared)
        val next = WorkspaceSkillGitHubInstallApproval.acceptSkill(
            prepared,
            response(first.url.toString(), body = file("SKILL.md", skill())),
            10L,
        )
        val validated = WorkspaceSkillGitHubInstallApproval.acceptManifest(
            next.state,
            response(next.request.url.toString(), 404, "{}"),
            11L,
        )
        assertEquals(
            WorkspaceSkillContract.Origin.GITHUB_PINNED,
            validated.fresh.skill.provenance.origin,
        )
        assertEquals(sha, validated.fresh.skill.provenance.pinnedRevision)

        val store = WorkspaceSkillStore(temp.newFolder("github-install"))
        val installed = store.installNew(
            skill = validated.fresh.skill,
            packageFiles = validated.fresh.packageFiles,
            approval = validated.fresh.approval,
            approvedToken =
                validated.prepared.installPrepared.approval.approvalToken,
            installedAtMs = 100L,
        )
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            installed.entry.state,
        )
        assertEquals(
            WorkspaceSkillContract.Origin.GITHUB_PINNED,
            installed.entry.provenance.origin,
        )
        assertEquals(sha, installed.entry.provenance.pinnedRevision)
    }

    @Test fun manifestPresenceOrPinnedBytesChangingAfterApprovalFailClosed() {
        val preparedWithoutManifest =
            WorkspaceSkillGitHubInstallApproval.prepare(completion())
        val first = WorkspaceSkillGitHubInstallApproval.firstRequest(
            preparedWithoutManifest)
        val next = WorkspaceSkillGitHubInstallApproval.acceptSkill(
            preparedWithoutManifest,
            response(first.url.toString(), body = file("SKILL.md", skill())),
            10L,
        )
        assertTrue(runCatching {
            WorkspaceSkillGitHubInstallApproval.acceptManifest(
                next.state,
                response(
                    next.request.url.toString(),
                    body = file("skill.json", "{}"),
                ),
                11L,
            )
        }.isFailure)

        val prepared = WorkspaceSkillGitHubInstallApproval.prepare(completion())
        val changed = skill().replace("Install pinned", "Changed pinned")
        val changedFirst = WorkspaceSkillGitHubInstallApproval.firstRequest(prepared)
        val changedNext = WorkspaceSkillGitHubInstallApproval.acceptSkill(
            prepared,
            response(
                changedFirst.url.toString(),
                body = file("SKILL.md", changed),
            ),
            12L,
        )
        assertTrue(runCatching {
            WorkspaceSkillGitHubInstallApproval.acceptManifest(
                changedNext.state,
                response(changedNext.request.url.toString(), 404, "{}"),
                13L,
            )
        }.isFailure)
    }
}
