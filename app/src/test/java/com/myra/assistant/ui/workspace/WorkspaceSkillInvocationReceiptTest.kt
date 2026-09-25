package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceSkillInvocationReceiptTest {
    private fun projection(
        origin: WorkspaceSkillInvocation.Origin =
            WorkspaceSkillInvocation.Origin.USER_EXPLICIT,
    ) = WorkspaceSkillInvocation.Projection(
        skillName = "review-code",
        contentSha256 = "a".repeat(64),
        packageSha256 = "b".repeat(64),
        permissionSha256 = "c".repeat(64),
        invocationSha256 = "d".repeat(64),
        prompt = "SECRET SKILL BODY MUST NOT APPEAR",
        origin = origin,
        taskId = "chat-1",
        turnId = "turn-1",
        sourceRevision = null,
    )

    @Test fun receiptShowsOnlyShortImmutableFingerprintsAndOneTurnScope() {
        val text = WorkspaceSkillInvocationReceipt.text(projection())
        assertTrue(text.contains("review-code"))
        assertTrue(text.contains("one turn"))
        assertTrue(text.contains("a".repeat(12)))
        assertTrue(text.contains("b".repeat(12)))
        assertTrue(text.contains("c".repeat(12)))
        assertTrue(text.contains("d".repeat(12)))
        assertFalse(text.contains("SECRET SKILL BODY"))
        assertTrue(text.contains("not proof", ignoreCase = true))
    }

    @Test fun attachLeavesNormalRepliesByteForByteUnchanged() {
        val reply = "Normal reply."
        assertEquals(reply, WorkspaceSkillInvocationReceipt.attach(reply, null))
    }

    @Test fun attachAddsReceiptAfterReplyWithoutChangingReplyText() {
        val attached = WorkspaceSkillInvocationReceipt.attach("Provider reply.", projection())
        assertTrue(attached.startsWith("Provider reply.\n\nSkill receipt"))
        assertFalse(attached.contains("SECRET SKILL BODY"))
    }

    @Test fun nonExplicitOriginAndMalformedHashesFailClosed() {
        assertTrue(runCatching {
            WorkspaceSkillInvocationReceipt.text(
                projection(WorkspaceSkillInvocation.Origin.MODEL_SELECTED))
        }.isFailure)
        assertTrue(runCatching {
            WorkspaceSkillInvocationReceipt.text(
                projection().copy(contentSha256 = "short"))
        }.isFailure)
    }
}
