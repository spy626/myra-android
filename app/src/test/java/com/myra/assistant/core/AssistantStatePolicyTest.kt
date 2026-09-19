package com.myra.assistant.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStatePolicyTest {
    @Test fun speakingReturnsThroughResumeState() {
        assertTrue(AssistantStatePolicy.canTransition(AssistantState.SPEAKING, AssistantState.RESUMING_WAKE_WORD))
        assertTrue(AssistantStatePolicy.canTransition(AssistantState.RESUMING_WAKE_WORD, AssistantState.WAKE_WORD_LISTENING))
    }
    @Test fun stoppedCannotExecuteImmediately() {
        assertFalse(AssistantStatePolicy.canTransition(AssistantState.STOPPED, AssistantState.EXECUTING_ACTION))
    }
}
