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

    @Test fun finalizedAccumulatorResetKeepsConsecutiveQuestionsSeparate() {
        val text = StringBuilder()
        LiveTranscriptAssembler.append(text, "Mera best friend kaun hai?")
        val first = text.toString()
        text.clear() // same reset performed at each service turnComplete boundary
        LiveTranscriptAssembler.append(text, "Mere baare mein kya jaante ho?")
        assertEquals("Mera best friend kaun hai?", first)
        assertEquals("Mere baare mein kya jaante ho?", text.toString())
    }
}
