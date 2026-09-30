package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceWorkKickoffTest {
    @Test fun hinglishCommentTaskMentionsExactFileAndCiWithoutClaimingSuccess() {
        val text = WorkspaceWorkKickoff.github(
            "Bro GitHub repo me ReadingTrackerSafetyTest.kt me safe comment-only change karo, " +
                "agent/myra-phase-1 feature branch par push karo aur exact CI GREEN tak verify karo."
        )

        assertTrue(text.startsWith("Haan bro"))
        assertTrue(text.contains("ReadingTrackerSafetyTest.kt"))
        assertTrue(text.contains("comment-only"))
        assertTrue(text.contains("feature branch"))
        assertTrue(text.contains("exact CI"))
        assertFalse(text.contains("CI GREEN ho gaya", ignoreCase = true))
        assertFalse(text.contains("passed", ignoreCase = true))
        assertFalse(text.contains("complete", ignoreCase = true))
    }

    @Test fun differentTaskProducesDifferentSituationSpecificKickoff() {
        val comment = WorkspaceWorkKickoff.github(
            "Bro GitHub repo me Foo.kt me safe comment-only change add karo aur exact CI GREEN tak verify karo."
        )
        val removal = WorkspaceWorkKickoff.github(
            "Bro GitHub repo me Bar.kt se old helper remove karo. Sirf feature branch par rakho."
        )

        assertNotEquals(comment, removal)
        assertTrue(comment.contains("Foo.kt"))
        assertTrue(comment.contains("comment-only"))
        assertTrue(removal.contains("Bar.kt"))
        assertTrue(removal.contains("removal"))
    }

    @Test fun englishRequestUsesEnglishKickoff() {
        val text = WorkspaceWorkKickoff.github(
            "Update GitHub repo file src/State.kt with the requested fix on the feature branch and verify exact CI."
        )

        assertTrue(text.startsWith("Got it"))
        assertTrue(text.contains("State.kt"))
        assertTrue(text.contains("feature branch"))
        assertTrue(text.contains("exact CI"))
    }

    @Test fun sensitiveRequestDoesNotEchoSensitiveContent() {
        val text = WorkspaceWorkKickoff.github(
            "Bro GitHub repo me Api.kt update karo api_key=sk-super-secret-123456"
        )

        assertTrue(text.startsWith("Haan bro"))
        assertFalse(text.contains("Api.kt"))
        assertFalse(text.contains("super-secret"))
        assertTrue(text.contains("safe bounded scope"))
    }
}
