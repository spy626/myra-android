package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {
    @Test
    fun delayedFriendReplyAdviceIsNotAWhatsAppCommand() {
        assertNull(
            CommandParser.parse(
                "mera dost mujhe ignore kar raha hai aur message ka reply do din baad deta hai main kya karun"
            )
        )
    }

    @Test
    fun rephrasedDelayedReplyAdviceIsNotAWhatsAppCommand() {
        assertNull(
            CommandParser.parse(
                "woh mujhe message karta hai phir main reply deta hoon aur woh do din baad reply karta hai"
            )
        )
    }

    @Test
    fun explicitReplyPromptStillStartsWhatsAppFlow() {
        assertTrue(CommandParser.parse("reply karo") is AppCommand.ReplyWhatsApp)
    }

    @Test
    fun explicitMessageSendStillStartsWhatsAppFlow() {
        assertTrue(CommandParser.parse("message bhejo") is AppCommand.ReplyWhatsApp)
    }
    @Test fun explicitRememberStatementIsMemoryIntentNotPhoneCommand() {
        val text = "Remember that I like horror movies"
        assertTrue(CommandParser.isMemoryIntent(text))
        assertNull(CommandParser.parse(text))
    }

    @Test fun memoryQuestionIsNotPhoneCommand() {
        val text = "What do you remember about me"
        assertTrue(CommandParser.isMemoryIntent(text))
        assertNull(CommandParser.parse(text))
    }

    @Test fun forgetStatementIsNotPhoneCommand() {
        val text = "Forget that I like horror movies"
        assertTrue(CommandParser.isMemoryIntent(text))
        assertNull(CommandParser.parse(text))
    }

    @Test fun hinglishRememberStatementIsProtected() {
        assertTrue(CommandParser.isMemoryIntent("Yaad rakhna ki mujhe horror movies pasand hain"))
    }

    @Test fun friendArrivingOnTimeIsConversationNotClockCommand() {
        assertNull(CommandParser.parse("meri dost time par nahi aaye toh kya karna chahiye"))
        assertNull(CommandParser.parse("meri dost ya koi bhi time par nahi aayega toh kya karna chahiye"))
    }

    @Test fun durationQuestionIsConversationNotClockCommand() {
        assertNull(CommandParser.parse("24 hours mein kitna time hota hai"))
    }

    @Test fun directClockQuestionsStillWork() {
        assertTrue(CommandParser.parse("time kya hai") is AppCommand.CurrentTime)
        assertTrue(CommandParser.parse("abhi kitne baje hain") is AppCommand.CurrentTime)
        assertTrue(CommandParser.parse("normal time") is AppCommand.CurrentTime)
    }

    @Test fun ordinaryMessageConversationIsNotNotificationQuery() {
        assertNull(CommandParser.parse("normal conversation mein message ka reply kaise doon"))
        assertTrue(!CommandParser.isExplicitWhatsAppMessageQuery("mere baare mein kya jaante ho tum"))
        assertTrue(CommandParser.isExplicitWhatsAppMessageQuery("WhatsApp message aaya hai kya"))
    }

    @Test fun standaloneTimeUsesLocalClockCommand() {
        assertTrue(CommandParser.parse("time") is AppCommand.CurrentTime)
    }

    @Test fun durationQuestionIsNotEvenAProbablePhoneAction() {
        val text = "24 hours mein kitna time hota hai"
        assertNull(CommandParser.parse(text))
        assertTrue(!CommandParser.isProbableDeviceAction(text))
    }

    @Test fun conversationalTimeReferenceIsNotAProbablePhoneAction() {
        assertTrue(!CommandParser.isProbableDeviceAction("meri dost time par nahi aaye toh kya karna chahiye"))
    }

    @Test fun onlyStandaloneMessageIsAmbiguous() {
        assertTrue(CommandParser.isAmbiguousMessageReference("message"))
        assertTrue(!CommandParser.isAmbiguousMessageReference("message ka reply late aata hai"))
        assertTrue(CommandParser.parse("message bhejo") is AppCommand.ReplyWhatsApp)
    }
}
