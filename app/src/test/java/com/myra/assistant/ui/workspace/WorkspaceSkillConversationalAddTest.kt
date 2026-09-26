package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSkillConversationalAddTest {
    @get:Rule val temp = TemporaryFolder()

    private fun skillBytes(
        name: String = "code-explainer",
        description: String = "Explain code in plain language.",
    ): ByteArray = """---
name: $name
description: $description
license: MIT
---
# Workflow
1. Read the code.
2. Explain it clearly.

## Verification
Confirm every referenced symbol exists.
""".trimIndent().toByteArray()

    private fun installSimple(
        store: WorkspaceSkillStore,
        name: String,
        at: Long,
    ) {
        val md = skillBytes(name, "A separate installed skill.")
        val prepared = WorkspaceSkillInstallApproval.prepare(md)
        val fresh = WorkspaceSkillInstallApproval.revalidate(prepared, md)
        store.installNew(
            skill = fresh.skill,
            packageFiles = fresh.packageFiles,
            approval = fresh.approval,
            approvedToken = fresh.approval.approvalToken,
            installedAtMs = at,
        )
    }

    @Test fun naturalAddAndConfirmationIntentsAreLocalAndDeterministic() {
        assertEquals(
            WorkspaceSkillConversationalAdd.Intent.ADD_REQUEST,
            WorkspaceSkillConversationalAdd.classify(
                "Is skill ko add karo",
                awaitingConfirmation = false,
            ),
        )
        assertEquals(
            WorkspaceSkillConversationalAdd.Intent.CONFIRM,
            WorkspaceSkillConversationalAdd.classify(
                "Haan add karo",
                awaitingConfirmation = true,
            ),
        )
        assertEquals(
            WorkspaceSkillConversationalAdd.Intent.CANCEL,
            WorkspaceSkillConversationalAdd.classify(
                "Nahi, mat add karo",
                awaitingConfirmation = true,
            ),
        )
        assertEquals(
            WorkspaceSkillConversationalAdd.Intent.OTHER,
            WorkspaceSkillConversationalAdd.classify(
                "Explain this file",
                awaitingConfirmation = false,
            ),
        )
    }

    @Test fun oneReviewedApprovalInstallsThenEnablesThroughExistingSafetyContracts() {
        val store = WorkspaceSkillStore(temp.newFolder("conversation-add"))
        val md = skillBytes()

        val prepared = WorkspaceSkillConversationalAdd.prepare(
            skillMdBytes = md,
            store = store,
            testedAtMs = 10L,
        )

        assertEquals("code-explainer", prepared.skillName)
        assertTrue(prepared.userSummary.contains("What it does: Explain code in plain language."))
        assertTrue(prepared.userSummary.contains("readiness checks passed"))
        assertTrue(prepared.userSummary.contains("Is skill ko add karna hai?"))
        assertFalse(prepared.userSummary.contains("SHA-256"))
        assertTrue(store.listVerified().isEmpty())

        val applied = WorkspaceSkillConversationalAdd.applyApproved(
            prepared = prepared,
            skillMdBytes = md,
            store = store,
            confirmedAtMs = 11L,
        )

        assertEquals(
            WorkspaceSkillCatalog.State.ENABLED,
            applied.installed.entry.state,
        )
        assertEquals(
            WorkspaceSkillCatalog.State.ENABLED,
            store.load("code-explainer").entry.state,
        )
    }

    @Test fun environmentChangeAfterReviewFailsBeforeTargetInstallation() {
        val store = WorkspaceSkillStore(temp.newFolder("stale-environment"))
        val md = skillBytes()
        val prepared = WorkspaceSkillConversationalAdd.prepare(
            skillMdBytes = md,
            store = store,
            testedAtMs = 20L,
        )

        installSimple(store, "other-skill", 21L)

        val result = runCatching {
            WorkspaceSkillConversationalAdd.applyApproved(
                prepared = prepared,
                skillMdBytes = md,
                store = store,
                confirmedAtMs = 22L,
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("changed after review"))
        assertTrue(runCatching { store.load("code-explainer") }.isFailure)
        assertEquals(
            WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
            store.load("other-skill").entry.state,
        )
    }

    @Test fun changedBytesCannotReuseEarlierChatApproval() {
        val store = WorkspaceSkillStore(temp.newFolder("changed-bytes"))
        val original = skillBytes()
        val prepared = WorkspaceSkillConversationalAdd.prepare(
            skillMdBytes = original,
            store = store,
            testedAtMs = 30L,
        )
        val changed = skillBytes(description = "Changed after review.")

        val result = runCatching {
            WorkspaceSkillConversationalAdd.applyApproved(
                prepared = prepared,
                skillMdBytes = changed,
                store = store,
                confirmedAtMs = 31L,
            )
        }

        assertTrue(result.isFailure)
        assertTrue(store.listVerified().isEmpty())
    }
}
