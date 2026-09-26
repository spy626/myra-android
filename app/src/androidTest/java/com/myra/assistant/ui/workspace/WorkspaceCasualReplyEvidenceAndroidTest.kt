package com.myra.assistant.ui.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on Android's ICU-backed regex engine, not the desktop JVM regex engine. */
@RunWith(AndroidJUnit4::class)
class WorkspaceCasualReplyEvidenceAndroidTest {
    private fun turn(role: String, text: String, index: Int) =
        WorkspaceConversationStore.Message("android-$index", role, text, index.toLong())

    @Test fun casualVerifierInitializesAndPreservesOrdinaryHinglishReply() {
        val messages = listOf(
            turn("user", "Kal main library jaane ka plan kar raha hoon. Dost ki tarah short reply dena.", 1),
            turn("assistant", "Achha, library jaane ka plan hai.", 2),
            turn("user", "Sahi hai 😄", 3)
        )
        assertEquals("Achha, samajh gaya 😄", WorkspaceChatTurnFrame.verify(messages,
            "Achha, samajh gaya 😄"))
        assertTrue(WorkspaceChatTurnFrame.instructions(messages).contains("brief acknowledgement"))
    }

    @Test fun verifierStillRejectsAnUninvitedMeetingWithoutCrashing() {
        val messages = listOf(turn("user", "Kal main library jaane ka plan kar raha hoon. Dost ki tarah short reply dena.", 1))
        val error = runCatching {
            WorkspaceChatTurnFrame.verify(messages, "Kal library mein milte hain!")
        }.exceptionOrNull()
        assertTrue("Expected a scoped reply rejection rather than class initialization failure: $error",
            error is IllegalArgumentException && error.message?.contains("meeting") == true)
    }

    @Test fun aMeetingQuestionAboutSomeoneElseIsNotBlocked() {
        val messages = listOf(
            turn("user", "Kal main library jaane ka plan kar raha hoon. Dost ki tarah short reply dena.", 1),
            turn("assistant", "Achha, library wala plan.", 2),
            turn("user", "Sahi hai 😄", 3)
        )
        val reply = "Kya tum library mein kisi dost se milne wale ho?"
        assertEquals(reply, WorkspaceChatTurnFrame.verify(messages, reply))
    }

    @Test fun exactPreviousAnswerOnNewEmojiAcknowledgementIsNotSaved() {
        val repeated = "Library ka plan sahi hai."
        val messages = listOf(
            turn("user", "Kal main library jaane ka plan kar raha hoon. Dost ki tarah short reply dena.", 1),
            turn("assistant", repeated, 2),
            turn("user", "Sahi hai 😄", 3)
        )
        val failure = runCatching { WorkspaceChatTurnFrame.verify(messages, repeated) }.exceptionOrNull()
        assertTrue("Expected exact echo to be rejected: $failure",
            failure is IllegalArgumentException && failure.message?.contains("repeated") == true)
    }
}
