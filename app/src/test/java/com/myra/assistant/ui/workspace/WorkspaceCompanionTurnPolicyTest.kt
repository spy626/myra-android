package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCompanionTurnPolicyTest {
    @Test fun seriousTasksDoNotTriggerJokes() {
        assertEquals(WorkspaceCompanionTurnPolicy.Kind.TASK,
            WorkspaceCompanionTurnPolicy.kind(
                "Android WorkManager aur Foreground Service difference sources ke saath explain"))
        assertTrue(WorkspaceCompanionTurnPolicy.instructions(
            "LYRA GitHub repo architecture fix karo").contains("serious task"))
    }
    @Test fun casualConversationAllowsNaturalLiveliness() {
        assertEquals(WorkspaceCompanionTurnPolicy.Kind.SOCIAL,
            WorkspaceCompanionTurnPolicy.kind("bro mai bored hu kuch funny baat karo"))
        assertFalse(WorkspaceCompanionTurnPolicy.instructions(
            "bro mai bored hu kuch funny baat karo").contains("Do the actual work first"))
    }
    @Test fun ordinaryInputDoesNotForcePersona() {
        assertEquals("", WorkspaceCompanionTurnPolicy.instructions(
            "I will be home in twenty minutes"))
    }
}
