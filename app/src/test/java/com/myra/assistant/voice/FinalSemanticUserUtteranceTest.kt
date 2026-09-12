package com.myra.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalSemanticUserUtteranceTest {
    @Test fun conversationTruthKeepsRawCanonicalTextSeparateFromDisplay() {
        val formatted = FinalTranscriptDisplayFormatter.Result("Mera dost Samir hai.", "Mera dost Samir hai.",
            false, true, listOf("Samir"), emptyList())
        val turn = FinalSemanticUserUtterance.from("session", 7, "मेरा दोस्त समीर है।", formatted)
        assertEquals("मेरा दोस्त समीर है।", turn.canonicalSemanticText)
        assertEquals("Mera dost Samir hai.", turn.displayText)
        assertEquals(listOf("मेरा दोस्त समीर है।", "Mera dost Samir hai."), turn.memoryEvidence.variants)
        assertTrue(turn.semanticConsistency)
    }

    @Test fun displayDamageNeverReplacesCanonicalAuthority() {
        val formatted = FinalTranscriptDisplayFormatter.Result("Nice jao.", "Nice jao.", false, false, emptyList(), emptyList())
        val turn = FinalSemanticUserUtterance.from("s", 8, "नीचे जाओ।", formatted)
        assertEquals("नीचे जाओ।", turn.memoryEvidence.canonicalText)
        assertEquals("Nice jao.", turn.memoryExtractorInput)
    }
}
