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
}
