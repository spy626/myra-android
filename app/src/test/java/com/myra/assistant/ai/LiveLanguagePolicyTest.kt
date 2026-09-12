package com.myra.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveLanguagePolicyTest {
    @Test fun pinsLiveSpeechToIndianEnglishHindiBundle() {
        assertEquals("en-IN", LiveLanguagePolicy.SPEECH_LANGUAGE_CODE)
    }
}
