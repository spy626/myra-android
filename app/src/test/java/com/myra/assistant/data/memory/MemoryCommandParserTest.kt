package com.myra.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCommandParserTest {
    @Test fun parsesJustRemember() {
        val command = MemoryCommandParser.parse("Just remember that I like horror movies")
        assertTrue(command is MemoryCommand.Remember)
        command as MemoryCommand.Remember
        assertEquals(MemoryCategory.PREFERENCE, command.candidate.category)
        assertEquals("i like horror movies", command.candidate.stableKey.removePrefix("preference:"))
    }

    @Test fun parsesReadRequest() {
        assertTrue(MemoryCommandParser.parse("What do you remember about me?") is MemoryCommand.Read)
    }

    @Test fun parsesForgetRequest() {
        val command = MemoryCommandParser.parse("Forget that I like horror movies")
        assertTrue(command is MemoryCommand.Forget)
        assertEquals("i like horror movies", (command as MemoryCommand.Forget).query)
    }

    @Test fun marksCredentialsProhibited() {
        val command = MemoryCommandParser.parse("Remember that my password is secret123")
        assertNotNull(command)
        assertEquals(MemorySensitivity.PROHIBITED, (command as MemoryCommand.Remember).candidate.sensitivity)
    }

    @Test fun classifiesAgeAsIdentityWithReplaceableKey() {
        val command = MemoryCommandParser.parse("Remember that my age is 24") as MemoryCommand.Remember
        assertEquals(MemoryCategory.IDENTITY, command.candidate.category)
        assertEquals("identity:age", command.candidate.stableKey)
    }
    @Test fun parsesNaturalRecallVariants() {
        val variants = listOf(
            "What remember about me",
            "What you remember about me",
            "What all do you remember about me",
            "Tumhe mere baare mein kya yaad hai"
        )
        variants.forEach { assertTrue(it, MemoryCommandParser.parse(it) is MemoryCommand.Read) }
    }

    @Test fun stripsLeadingThisFromRememberedFact() {
        val command = MemoryCommandParser.parse("Remember this I love horror movies") as MemoryCommand.Remember
        assertEquals("I love horror movies", command.candidate.fact)
        assertEquals("I love horror movies", command.displayFact)
    }

}
