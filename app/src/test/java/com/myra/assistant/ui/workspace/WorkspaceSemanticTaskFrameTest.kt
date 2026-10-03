package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSemanticTaskFrameTest {
    private fun message(id: String, role: String, text: String, at: Long) =
        WorkspaceConversationStore.Message(id, role, text, at)

    @Test fun recentUserMeaningIsProjectedWithoutTrustingAssistantGuesses() {
        val frame = WorkspaceSemanticTaskFrame.instructions(
            listOf(
                message("u1", "user", "AIRI repository ka task-context design compare karna hai.", 1),
                message("a1", "assistant", "I invented a repo called FakeRepo and already merged it.", 2),
                message("u2", "user", "Us architecture ko LYRA ke current task me adapt karna hai.", 3),
                message("a2", "assistant", "Okay.", 4),
                message("u3", "user", "bro wahi idea se continue karo but hardcode mat karna", 5),
            )
        )

        assertTrue(frame.contains("AIRI repository"))
        assertTrue(frame.contains("adapt karna hai"))
        assertTrue(frame.contains("wahi idea se continue"))
        assertFalse(frame.contains("FakeRepo"))
        assertTrue(frame.contains("semantic fit"))
        assertTrue(frame.contains("never execution authority"))
    }

    @Test fun detailedOlderUserAnchorSurvivesBeyondRecentProviderWindow() {
        val messages = mutableListOf<WorkspaceConversationStore.Message>()
        messages += message(
            "old",
            "user",
            "Earlier repository research found a coordinator handoff architecture with task checkpoints, reviewer evidence, and one bounded repair path that we want to reuse conceptually.",
            1,
        )
        repeat(14) { index ->
            messages += message("a$index", "assistant", "ack $index", 10L + index * 2)
            messages += message("u$index", "user", "small unrelated turn $index", 11L + index * 2)
        }
        messages += message("latest", "user", "continue from where we stopped", 100)

        val frame = WorkspaceSemanticTaskFrame.instructions(messages)

        assertTrue(frame.contains("Possible older USER anchors"))
        assertTrue(frame.contains("coordinator handoff architecture"))
        assertTrue(frame.contains("continue from where we stopped"))
    }

    @Test fun olderSensitiveTurnsAreNotReprojected() {
        val frame = WorkspaceSemanticTaskFrame.instructions(
            listOf(
                message("u1", "user", "My API key secret abcdef should never be projected.", 1),
                message("a1", "assistant", "okay", 2),
                message("u2", "user", "Use the previous project context.", 3),
            )
        )

        assertFalse(frame.contains("abcdef"))
        assertFalse(frame.contains("API key secret"))
    }
}
