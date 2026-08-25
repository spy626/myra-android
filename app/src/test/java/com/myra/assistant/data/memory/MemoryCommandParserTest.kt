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


    @Test fun parsesLyraHinglishRememberVariant() {
        val command = MemoryCommandParser.parse(
            "Lyra yaad rako mujhe horror movie bohot pasand hai"
        ) as MemoryCommand.Remember
        assertEquals(MemoryCategory.PREFERENCE, command.candidate.category)
        assertEquals("mujhe horror movie bohot pasand hai", command.displayFact)
    }

    @Test fun stripsLeadingThisFromRememberedFact() {
        val command = MemoryCommandParser.parse("Remember this I love horror movies") as MemoryCommand.Remember
        assertEquals("I love horror movies", command.candidate.fact)
        assertEquals("I love horror movies", command.displayFact)
    }


    @Test fun parsesObservedImperfectRecallTranscripts() {
        listOf(
            "tumhem mere bare mem kya yada hai",
            "tumhen mere bare me kya yaad hai",
            "abhi mere bare mem kya pata hai",
            "mere baare mein kya yaad hai",
            "mere bare mem kya janate ho",
            "mere baare mein tum kya jaante ho",
            "mere baare mein kya jaanti ho",
            "mere bare mem kya janati ho"
        ).forEach { phrase ->
            assertTrue(phrase, MemoryCommandParser.looksLikeIntent(phrase))
            assertTrue(phrase, MemoryCommandParser.parse(phrase) is MemoryCommand.Read)
        }
    }

    @Test fun parsesBestFriendRecallQuestions() {
        listOf(
            "Kon meri best frend hai",
            "Kauna meri best friend hai",
            "Mera best friend kaun hai",
            "Meri best friend kaun hai",
            "Who is my best friend"
        ).forEach { phrase ->
            assertTrue(phrase, MemoryCommandParser.looksLikeIntent(phrase))
            val command = MemoryCommandParser.parse(phrase) as MemoryCommand.Read
            assertEquals("best friend", command.query)
        }
    }

    @Test fun parsesNaturalRelationshipRemoval() {
        listOf(
            "Kareem ko meri memory se hata do",
            "Kareem ko memory se delete kar do",
            "Kareem ko memory se delete karo",
            "Kareem mera friend nahi hai ye bhool jao"
        ).forEach { phrase ->
            assertTrue(phrase, MemoryCommandParser.looksLikeIntent(phrase))
            val command = MemoryCommandParser.parse(phrase) as MemoryCommand.Forget
            assertEquals("kareem", command.query)
        }
    }

    @Test fun acceptsCommonAsrDeleteWording() {
        val command = MemoryCommandParser.parse("Kareen ko memory se delete kero") as MemoryCommand.Forget
        assertEquals("kareen", command.query)
    }

    @Test fun parsesNaturalPreferenceRemovalFromObservedRecording() {
        val command = MemoryCommandParser.parse(
            "Tumhare like na ghumna memory se delete kar do"
        ) as MemoryCommand.Forget
        assertEquals("na ghumna", command.query)
    }

    @Test fun parsesPluralMemoryRemovalFromObservedAsr() {
        val command = MemoryCommandParser.parse(
            "ghumana memories delete kar do"
        ) as MemoryCommand.Forget
        assertEquals("ghumana", command.query)
    }
}
