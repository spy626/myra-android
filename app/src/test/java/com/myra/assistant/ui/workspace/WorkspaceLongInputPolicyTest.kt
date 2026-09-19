package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLongInputPolicyTest {
    private fun message(text: String) = WorkspaceConversationStore.Message("user", "user", text, 1L)

    @Test fun longPasteCanBeSavedWithoutSlicing() {
        assertTrue(WorkspaceLongInputPolicy.sendable("a".repeat(20_000)))
        assertTrue(WorkspaceLongInputPolicy.sendable("b".repeat(WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS)))
        assertFalse(WorkspaceLongInputPolicy.sendable("c".repeat(WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS + 1)))
    }

    @Test fun remoteLimitChecksWholeTextNotTruncatedPrefix() {
        assertTrue(WorkspaceLongInputPolicy.requestFits(listOf(message("a".repeat(32_000)))))
        assertTrue(WorkspaceLongInputPolicy.requestFits(listOf(message("b".repeat(40_000)), message("c".repeat(30_000)))))
        val latest = message("c".repeat(30_000))
        val outgoing = WorkspaceLongInputPolicy.outbound(listOf(message("b".repeat(40_000)), latest))
        assertTrue(outgoing.size == 2)
        assertTrue(outgoing.last().text == latest.text)
        val overBudget = WorkspaceLongInputPolicy.outbound(listOf(
            message("b".repeat(40_000)), message("c".repeat(64_000))))
        assertTrue(overBudget.size == 1)
        assertTrue(overBudget.single().text == "c".repeat(64_000))
        val history = (1..30).map { message("turn-$it " + "x".repeat(1_000)) }
        val recent = WorkspaceLongInputPolicy.outbound(history)
        assertTrue(recent.size == WorkspaceLongInputPolicy.MAX_RECENT_MESSAGES)
        assertTrue(recent.first().text == history[6].text)
        assertFalse(WorkspaceLongInputPolicy.requestFits(listOf(message("z".repeat(WorkspaceLongInputPolicy.MAX_REQUEST_CHARS + 1)))))
    }
}
