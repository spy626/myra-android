package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChatResponseSafetyTest {
    private fun turn(role: String, text: String, n: Int) =
        WorkspaceConversationStore.Message("$n", role, text, n.toLong())

    @Test fun completedSpeechSeparatesWellFormedReasoningWithoutLeaking() {
        assertEquals("Achha, samjha. 🙂", WorkspaceChatVisibleReply.sanitize(
            "<think>Unshown private tokens</think>Achha, samjha. 🙂"))
        assertEquals("Pehle suno, phir batao.", WorkspaceChatVisibleReply.sanitize(
            "Pehle suno, <reasoning>private</reasoning> phir batao."))
        assertEquals("Okay", WorkspaceChatVisibleReply.sanitize("  Okay  "))
    }

    @Test fun orphanTagsIncompleteBlocksAndControlTokensNeverBecomeSavedChat() {
        val bad = listOf(
            "Main khud likh raha tha</think>Acha, ye to sahi hai.",
            "<think>private thought that never closed",
            "<think>only private</think>",
            "<analysis>hidden</analysis></reasoning>Oops",
            "Hi <|im_start|>assistant",
            "Before <thinking"
        )
        bad.forEach { text ->
            val rejected = runCatching { WorkspaceChatVisibleReply.sanitize(text) }.exceptionOrNull()
            assertTrue("Must reject untrusted model control text: $text", rejected != null)
            assertFalse(rejected!!.message.orEmpty().contains("private thought"))
        }
    }

    @Test fun allNormalChatRepliesGoThroughSameBoundaryEvenWithoutShortPreference() {
        val user = listOf(turn("user", "Main kal swimming practice par jaunga. Dost jaisi baat karo.", 1))
        assertEquals("Wah, kaisa lag raha hai?", WorkspaceChatTurnFrame.verify(user,
            "<think>hidden</think>Wah, kaisa lag raha hai?"))
        assertTrue(runCatching { WorkspaceChatTurnFrame.verify(user,
            "Soch raha tha</think>Haan") }.isFailure)
        // Literal markup in an explicit technical task is not silently erased.
        val technical = listOf(turn("user", "Explain what the literal </think> token means in code.", 2))
        assertEquals("The literal </think> token is a delimiter.", WorkspaceChatTurnFrame.verify(
            technical, "The literal </think> token is a delimiter."))
    }

    @Test fun cancelledPlanIsQuotedWithoutClaimingUserMadeNoDecision() {
        val messages = listOf(
            turn("user", "Kal main library jaane ka plan kar raha hoon.", 1),
            turn("assistant", "Okay!", 2),
            turn("user", "Ab main library nahi jaunga.", 3),
            turn("assistant", "Theek hai.", 4),
            turn("user", "Toh ab mera kal ka plan kya hai?", 5))
        val answer = requireNotNull(WorkspaceChatPlanStatus.answer(messages))
        assertTrue(answer.contains("library nahi jaunga"))
        assertTrue(answer.contains("naya plan nahi bataya"))
        assertFalse(answer.contains("decide nahi kiya"))
        assertFalse(answer.contains("beach"))
    }

    @Test fun unrelatedOrReplacementPlansAreNeverOverriddenByLocalStatus() {
        val prefix = listOf(turn("user", "Kal main chess club join karunga.", 1))
        assertNull(WorkspaceChatPlanStatus.answer(prefix + turn("user", "Mera plan kya hai?", 2)))
        val changed = prefix + turn("user", "Club nahi jaunga, kal park jaunga.", 3)
        assertNull(WorkspaceChatPlanStatus.answer(changed + turn("user", "Mera plan kya hai?", 4)))
        assertNull(WorkspaceChatPlanStatus.answer(prefix + turn("user", "Club nahi jaunga.", 3) +
            turn("user", "Explain recursion.", 4) + turn("user", "Mera plan kya hai?", 5)))
        assertNull(WorkspaceChatPlanStatus.answer(prefix + turn("user", "Club nahi jaunga.", 3) +
            turn("user", "Kya matlab?", 4)))
    }
}
