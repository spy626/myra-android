package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChatHinglishEvidenceTest {
    private fun turn(role: String, text: String, index: Int) =
        WorkspaceConversationStore.Message("h-$index", role, text, index.toLong())

    @Test fun trailingCancellationPhraseIsRecognisedWithoutInventingAReplacement() {
        val messages = listOf(
            turn("user", "Kal main pottery class jaunga. Dost ki tarah short reply dena.", 1),
            turn("assistant", "Okay", 2),
            turn("user", "Ab pottery class nahi jaunga, plan cancel ho gaya.", 3),
            turn("assistant", "Samajh gaya", 4),
            turn("user", "Toh ab mera kal ka plan kya hai?", 5)
        )
        val answer = requireNotNull(WorkspaceChatPlanStatus.answer(messages))
        assertTrue(answer.contains("pottery class nahi jaunga"))
        assertTrue(answer.contains("Naya plan abhi mujhe nahi bataya"))
        assertFalse(answer.contains("decide nahi kiya"))
    }

    @Test fun explicitlyDecidedButUndisclosedPlanBeatsOldCancellation() {
        val messages = listOf(
            turn("user", "Kal main pottery class jaunga.", 1),
            turn("user", "Ab pottery class nahi jaunga, plan cancel ho gaya.", 2),
            turn("assistant", "Okay.", 3),
            turn("user", "Maine naya plan decide kar liya hai, lekin abhi tumhe bataya nahi.", 4),
            turn("assistant", "Achha", 5),
            turn("user", "Ab batao, kya maine koi naya plan decide kiya hai?", 6)
        )
        val answer = requireNotNull(WorkspaceChatPlanStatus.answer(messages))
        assertTrue(answer.contains("plan decide kar liya hai"))
        assertTrue(answer.contains("abhi mujhe nahi bataya"))
        assertFalse(answer.contains("pottery"))
        assertFalse(answer.contains("decide nahi kiya"))
        assertTrue(WorkspaceChatPlanStatus.answer(messages.dropLast(1) +
            turn("user", "Mera kal ka plan kya hai?", 7))!!.contains("kaunsa plan"))
    }

    @Test fun englishWithheldAndAmbiguityDoNotBecomeInventedPlans() {
        val english = listOf(
            turn("user", "I have decided on a new plan, but haven't told you yet.", 1),
            turn("user", "Have I decided on a new plan?", 2))
        assertTrue(WorkspaceChatPlanStatus.answer(english)!!.contains("Yes, you said"))
        val replaced = listOf(
            turn("user", "I will attend the chess tournament tomorrow.", 1),
            turn("user", "Tournament nahi jaunga, ab park jaunga.", 2),
            turn("user", "Mera plan kya hai?", 3))
        assertNull(WorkspaceChatPlanStatus.answer(replaced))
        assertNull(WorkspaceChatPlanStatus.answer(listOf(
            turn("user", "Kal gym ka plan cancel karun?", 1),
            turn("user", "Mera plan kya hai?", 2))))
        assertNull(WorkspaceChatPlanStatus.answer(listOf(
            turn("user", "Tournament nahi jaunga.", 1),
            turn("user", "Explain recursion with an example.", 2),
            turn("user", "Mera plan kya hai?", 3))))
    }

    @Test fun casualReplyCannotIntroduceANameOrAnUninvitedMeetup() {
        val conversation = listOf(
            turn("user", "Kal main painting workshop jaunga. Dost ki tarah short reply dena.", 1),
            turn("assistant", "Okay", 2),
            turn("user", "Sahi hai", 3)
        )
        assertTrue(runCatching { WorkspaceChatTurnFrame.verify(conversation,
            "Haan, Harshi ko bhi bula lena!") }.isFailure)
        assertTrue(runCatching { WorkspaceChatTurnFrame.verify(conversation,
            "Kal studio mein milte hain!") }.isFailure)
        assertEquals("Achha, samajh gaya 😄", WorkspaceChatTurnFrame.verify(conversation,
            "Achha, samajh gaya 😄"))
        val invited = listOf(turn("user", "Kal main Ayaan se milunga, phir Ayaan se milte hain.", 4),
            turn("user", "Theek hai", 5))
        assertEquals("Ayaan se milte hain.", WorkspaceChatTurnFrame.verify(invited,
            "Ayaan se milte hain."))
        val technical = listOf(turn("user", "Explain who Harshi is in this sample JSON.", 6))
        assertEquals("Harshi is a string value.", WorkspaceChatTurnFrame.verify(technical,
            "Harshi is a string value."))
    }

    @Test fun romanHindiKeepsGroqAndOpenRouterSystemContextAligned() {
        val messages = listOf(turn("user", "Kal main painting workshop jaunga. Dost ki tarah short reply dena.", 1),
            turn("user", "Sahi hai", 2))
        val body = JSONObject(WorkspaceChatGateway.openRouterBody(messages))
        val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("natural Roman Hindi/Hinglish"))
        assertTrue(system.contains("painting workshop"))
        val groq = JSONObject(WorkspaceGroqFree.body(messages))
        assertEquals(system, groq.getJSONArray("messages").getJSONObject(0).getString("content"))
    }
}
