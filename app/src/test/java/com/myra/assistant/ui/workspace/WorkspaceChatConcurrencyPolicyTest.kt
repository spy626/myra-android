package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChatConcurrencyPolicyTest {
    @Test fun backgroundGitHubTaskKeepsComposerInSendModeWithSeparateStop() {
        val state = WorkspaceChatConcurrencyPolicy.state(
            foregroundBusy = false,
            githubSelfEditRunning = true,
        )

        assertEquals(
            WorkspaceChatConcurrencyPolicy.ComposerAction.SEND,
            state.composerAction,
        )
        assertTrue(state.showGitHubStop)
        assertFalse(state.allowNewGitHubSelfEdit)
    }

    @Test fun foregroundReplyStillOwnsComposerStopWithoutCancellingGitHubPolicy() {
        val state = WorkspaceChatConcurrencyPolicy.state(
            foregroundBusy = true,
            githubSelfEditRunning = true,
        )

        assertEquals(
            WorkspaceChatConcurrencyPolicy.ComposerAction.STOP_FOREGROUND,
            state.composerAction,
        )
        assertTrue(state.showGitHubStop)
        assertFalse(state.allowNewGitHubSelfEdit)
    }

    @Test fun backgroundTraceCannotMoveOntoNewForegroundTurn() {
        assertTrue(WorkspaceChatConcurrencyPolicy.ownsVisibleTrace("task-turn", "task-turn"))
        assertTrue(WorkspaceChatConcurrencyPolicy.ownsVisibleTrace(null, "task-turn"))
        assertFalse(WorkspaceChatConcurrencyPolicy.ownsVisibleTrace("new-turn", "task-turn"))
        assertFalse(WorkspaceChatConcurrencyPolicy.ownsVisibleTrace("new-turn", null))
    }
}
