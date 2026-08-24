package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryConfirmationParserTest {
    @Test
    fun acceptsNaturalRomanConfirmations() {
        assertEquals(MemoryConfirmationDecision.YES, MemoryConfirmationParser.parse("Haan."))
        assertEquals(MemoryConfirmationDecision.YES, MemoryConfirmationParser.parse("haan theek hai"))
        assertEquals(MemoryConfirmationDecision.NO, MemoryConfirmationParser.parse("Nahi।"))
        assertEquals(MemoryConfirmationDecision.NO, MemoryConfirmationParser.parse("save mat karo"))
    }

    @Test
    fun acceptsObservedHindiScriptConfirmations() {
        assertEquals(MemoryConfirmationDecision.YES, MemoryConfirmationParser.parse("हाँ"))
        assertEquals(MemoryConfirmationDecision.YES, MemoryConfirmationParser.parse("ठीक है।"))
        assertEquals(MemoryConfirmationDecision.NO, MemoryConfirmationParser.parse("नहीं"))
        assertEquals(MemoryConfirmationDecision.NO, MemoryConfirmationParser.parse("रहने दो"))
    }

    @Test
    fun rejectsConversationThatIsNotAConfirmation() {
        assertNull(MemoryConfirmationParser.parse("haan mujhe horror movies pasand hain"))
        assertNull(MemoryConfirmationParser.parse("nahi pata kya karna chahiye"))
        assertNull(MemoryConfirmationParser.parse("mere dost ka message nahi aaya"))
    }
}
