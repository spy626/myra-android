package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryCommandReplyFormatterTest {
    @Test fun usesShortNaturalHinglishInsteadOfRoboticEnglish() {
        assertEquals("Theek hai, yaad rakhungi.", MemoryCommandReplyFormatter.rememberSaved())
        assertEquals("Theek hai, woh memory delete kar di.", MemoryCommandReplyFormatter.forgotten(true))
        assertEquals("Woh memory saved nahi mili.", MemoryCommandReplyFormatter.forgotten(false))
    }
}
