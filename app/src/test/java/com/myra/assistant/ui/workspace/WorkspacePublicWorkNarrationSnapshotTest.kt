package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePublicWorkNarrationSnapshotTest {
    @Test fun exactTurnRoundTripPreservesPublicNarrationOrderAndTime() {
        val messages = listOf(
            WorkspacePublicWorkMessage(
                key = "scope",
                statusLabel = "Scoped work to File.kt",
                text = "Scope clear hai bro.",
                atMs = 1_100L,
            ),
            WorkspacePublicWorkMessage(
                key = "review-first-accept",
                statusLabel = "Review accepted the proposed change",
                text = "Reviewer ne change clear kiya.",
                atMs = 2_200L,
            ),
        )

        val encoded = WorkspacePublicWorkNarrationSnapshot.encode(
            projectId = "chat_1",
            messageId = "turn-123",
            messages = messages,
        )
        val restored = WorkspacePublicWorkNarrationSnapshot.decode(
            raw = encoded,
            expectedProjectId = "chat_1",
            expectedMessageId = "turn-123",
        )

        assertEquals(messages, restored)
    }

    @Test fun staleProjectOrTurnCannotReuseAnotherTurnsNarration() {
        val encoded = WorkspacePublicWorkNarrationSnapshot.encode(
            projectId = "chat_1",
            messageId = "turn-123",
            messages = listOf(
                WorkspacePublicWorkMessage("scope", "Scoped work", "Scope clear.", 1_000L)
            ),
        )

        assertTrue(
            WorkspacePublicWorkNarrationSnapshot.decode(
                encoded,
                expectedProjectId = "chat_2",
                expectedMessageId = "turn-123",
            ).isEmpty()
        )
        assertTrue(
            WorkspacePublicWorkNarrationSnapshot.decode(
                encoded,
                expectedProjectId = "chat_1",
                expectedMessageId = "turn-999",
            ).isEmpty()
        )
    }

    @Test fun secretLikeEntryIsNeverRestored() {
        val encoded = WorkspacePublicWorkNarrationSnapshot.encode(
            projectId = "chat_1",
            messageId = "turn-123",
            messages = listOf(
                WorkspacePublicWorkMessage(
                    "safe",
                    "Checking scope",
                    "Only File.kt is selected.",
                    1_000L,
                ),
                WorkspacePublicWorkMessage(
                    "secret",
                    "Reading config",
                    "api_key=sk-super-secret-123456",
                    2_000L,
                ),
            ),
        )

        val restored = WorkspacePublicWorkNarrationSnapshot.decode(
            encoded,
            expectedProjectId = "chat_1",
            expectedMessageId = "turn-123",
        )

        assertEquals(listOf("safe"), restored.map { it.key })
    }

    @Test fun malformedOrOversizedSnapshotFailsClosed() {
        assertTrue(
            WorkspacePublicWorkNarrationSnapshot.decode(
                raw = "{not-json",
                expectedProjectId = "chat_1",
                expectedMessageId = "turn-123",
            ).isEmpty()
        )
        assertTrue(
            WorkspacePublicWorkNarrationSnapshot.decode(
                raw = "x".repeat(8_001),
                expectedProjectId = "chat_1",
                expectedMessageId = "turn-123",
            ).isEmpty()
        )
    }
}
