package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceHinglishReplyTest {
    private fun stub(value: String): String =
        WorkspaceHinglishReply.normalizeWith(value) {
            mapOf("पहले" to "pehle", "चार" to "chaar", "स्क्रीन" to "screen",
                "लिखो" to "likho", "नमस्ते" to "namaste", "और" to "aur",
                "उदाहरण" to "udaaharan", "ऐप" to "app")[it] ?: "romanized"
        }

    @Test fun modelMustWriteNaturallyInRomanHinglish() {
        val instructions = WorkspaceHinglishReply.PROMPT_RULE
        assertTrue(instructions.contains("Roman Hinglish"))
        assertTrue(instructions.contains("Never write Devanagari"))
        assertTrue(instructions.contains("U+0900"))
        assertTrue(instructions.contains("fenced code stays code"))
    }

    @Test fun headingsBulletsAndComparisonTableConvertHindiProse() {
        val raw = "## पहले चार स्क्रीन लिखो\n- नमस्ते\n| ऐप | उदाहरण |\n| --- | --- |"
        assertEquals("## pehle chaar screen likho\n- namaste\n| app | udaaharan |\n| --- | --- |",
            stub(raw))
    }

    @Test fun fencedCodeInlineCodeAndHttpsDestinationsStayByteExact() {
        val raw = "पहले `नमस्ते` लिखो\n" +
            "[स्क्रीन](https://example.com/हिंदी) और https://example.com/नमस्ते\n" +
            "```kotlin\nval title = \"नमस्ते\"\n```\n" +
            "~~~text\nनमस्ते\n~~~\nनमस्ते"
        val expected = "pehle `नमस्ते` likho\n" +
            "[screen](https://example.com/हिंदी) aur https://example.com/नमस्ते\n" +
            "```kotlin\nval title = \"नमस्ते\"\n```\n" +
            "~~~text\nनमस्ते\n~~~\nnamaste"
        assertEquals(expected, stub(raw))
    }

    @Test fun ordinaryRomanEnglishAndHinglishRemainUnchanged() {
        val text = "Bro 😂 pehle basic features decide karo.\n" +
            "**Step 1:** Home, Product, Cart, Checkout."
        assertEquals(text, stub(text))
    }

    @Test fun scriptFallbackNeverInventsRecommendations() {
        val original = "1. पहले चार स्क्रीन लिखो.\n2. नमस्ते"
        val result = stub(original)
        assertEquals("1. pehle chaar screen likho.\n2. namaste", result)
        assertFalse(result.contains("Android Studio"))
    }
}