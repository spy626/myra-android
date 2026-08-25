package com.myra.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTranscriptAssemblerTest {
    @Test fun preservesWordFragmentsAndApiWhitespace() {
        val text = StringBuilder()
        LiveTranscriptAssembler.append(text, "Mun")
        LiveTranscriptAssembler.append(text, "nar")
        LiveTranscriptAssembler.append(text, " bahut jagah")
        assertEquals("Munnar bahut jagah", text.toString())
    }

    @Test fun replacesCumulativeHypothesisInsteadOfDuplicatingIt() {
        val text = StringBuilder()
        LiveTranscriptAssembler.append(text, "Munnar")
        LiveTranscriptAssembler.append(text, "Munnar bahut jagah")
        assertEquals("Munnar bahut jagah", text.toString())
    }
}
