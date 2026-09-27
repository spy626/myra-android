package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillConversationalCreateTest {
    @get:Rule val temp = TemporaryFolder()

    private fun providerDraft(
        name: String = "friendly-explainer",
        description: String = "Explain difficult ideas in simple words.",
    ): String = """
LYRA_SKILL_DRAFT_BEGIN
---
name: $name
description: $description
---
# Instructions
Explain the user's topic simply, keep the explanation focused, and avoid adding unrelated claims.

## Verification
Check that the explanation answers the requested topic and uses plain language.
LYRA_SKILL_DRAFT_END
""".trimIndent()

    @Test fun shortProviderQuestionIsAccepted() {
        val parsed = WorkspaceSkillConversationalCreate.parseProviderReply(
            "LYRA_SKILL_QUESTION: Should this skill answer briefly or in detail?"
        )
        val question = parsed as WorkspaceSkillConversationalCreate.ProviderResult.Question
        assertEquals("Should this skill answer briefly or in detail?", question.text)
    }

    @Test fun boundedDraftIsParsedAndUsesMinimalPermissions() {
        val parsed = WorkspaceSkillConversationalCreate.parseProviderReply(providerDraft())
        val draft = parsed as WorkspaceSkillConversationalCreate.ProviderResult.Draft
        val skill = WorkspaceSkillContract.parse(
            draft.skillMdBytes.toString(Charsets.UTF_8),
            provenance = WorkspaceSkillContract.Provenance(
                WorkspaceSkillContract.Origin.LOCAL_DERIVED
            ),
        )
        assertEquals("friendly-explainer", skill.name)
        assertTrue(skill.hasVerificationGate)
        assertTrue(skill.permissionPreview.tools.isEmpty())
        assertTrue(skill.permissionPreview.networkDomains.isEmpty())
        assertEquals(WorkspaceSkillContract.MemoryAccess.NONE, skill.permissionPreview.memoryAccess)
    }

    @Test fun generatedDraftUsesExistingApprovalReadinessAndEnableContracts() {
        val store = WorkspaceSkillStore(temp.newFolder("create-skill"))
        val draft = WorkspaceSkillConversationalCreate.parseProviderReply(providerDraft())
            as WorkspaceSkillConversationalCreate.ProviderResult.Draft

        val prepared = WorkspaceSkillConversationalCreate.prepare(
            skillMdBytes = draft.skillMdBytes,
            store = store,
            testedAtMs = 10L,
        )

        assertEquals("friendly-explainer", prepared.skillName)
        assertTrue(prepared.userSummary.contains("Draft ready"))
        assertTrue(prepared.userSummary.contains("Haan create karo"))
        assertTrue(store.listVerified().isEmpty())

        val applied = WorkspaceSkillConversationalCreate.applyApproved(
            prepared = prepared,
            store = store,
            confirmedAtMs = 11L,
        )
        assertEquals(WorkspaceSkillCatalog.State.ENABLED, applied.installed.entry.state)
        assertEquals(
            WorkspaceSkillContract.Origin.LOCAL_DERIVED,
            applied.installed.entry.provenance.origin,
        )
    }

    @Test fun unsafeOrMalformedProviderDraftFailsBeforeInstall() {
        val store = WorkspaceSkillStore(temp.newFolder("unsafe-create"))
        val malformed = """
LYRA_SKILL_DRAFT_BEGIN
---
name: bad-skill
description: Missing verification.
---
# Instructions
Do something.
LYRA_SKILL_DRAFT_END
""".trimIndent()

        assertTrue(runCatching {
            WorkspaceSkillConversationalCreate.parseProviderReply(malformed)
        }.isFailure)
        assertTrue(store.listVerified().isEmpty())
    }
}
