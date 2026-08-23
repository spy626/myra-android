package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand
import org.junit.Assert.assertEquals
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
        assertEquals(
            AppCommand.ReplyWhatsApp(null, ""),
            CommandParser.parse("reply karo")
        )
    }

    @Test
    fun explicitMessageSendStillStartsWhatsAppFlow() {
        assertEquals(
            AppCommand.ReplyWhatsApp(null, ""),
            CommandParser.parse("message bhejo")
        )
    }
}
