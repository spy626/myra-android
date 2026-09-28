package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProviderBakeOffTest {
    @Test fun exactThreeFixesPass() {
        val raw = """{"t1":"return value % 2 == 0","t2":"return value.coerceIn(min, max)","t3":"return tags.firstOrNull().orEmpty()"}"""
        val result = WorkspaceProviderBakeOff.evaluate(raw)
        assertTrue(result.strictJson)
        assertEquals(3, result.correct)
        assertTrue(result.passed)
    }

    @Test fun equivalentKotlinSyntaxGetsSemanticCredit() {
        val raw = """{"t1":"return value.rem(2) == 0","t2":"return maxOf(min, minOf(max, value))","t3":"return tags.firstOrNull() ?: \"\""}"""
        val result = WorkspaceProviderBakeOff.evaluate(raw)
        assertTrue(result.strictJson)
        assertTrue(result.t1)
        assertTrue(result.t2)
        assertTrue(result.t3)
        assertTrue(result.passed)
    }

    @Test fun proseFailsFormatButKeepsCorrectnessSignal() {
        val result = WorkspaceProviderBakeOff.evaluate(
            "Here is the fix: {\"t1\":\"return value % 2 == 0\",\"t2\":\"return value.coerceIn(min, max)\",\"t3\":\"return tags.getOrNull(0).orEmpty()\"}"
        )
        assertFalse(result.strictJson)
        assertEquals(3, result.correct)
        assertFalse(result.passed)
    }

    @Test fun actuallyWrongThirdFixStaysWrong() {
        val result = WorkspaceProviderBakeOff.evaluate(
            """{"t1":"return value % 2 == 0","t2":"return value.coerceIn(min, max)","t3":"return tags.first()"}"""
        )
        assertEquals(2, result.correct)
        assertFalse(result.t3)
        assertFalse(result.passed)
    }
}
