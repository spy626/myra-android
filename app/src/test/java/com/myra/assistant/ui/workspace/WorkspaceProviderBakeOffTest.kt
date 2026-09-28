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

    @Test fun proseOrWrongFixDoesNotPass() {
        assertFalse(
            WorkspaceProviderBakeOff.evaluate(
                "Here: {\"t1\":\"return value % 2 == 0\",\"t2\":\"return value.coerceIn(min, max)\",\"t3\":\"return tags.firstOrNull().orEmpty()\"}"
            ).strictJson
        )
        val wrong = WorkspaceProviderBakeOff.evaluate(
            """{"t1":"return value % 2 == 0","t2":"return value.coerceIn(min, max)","t3":"return tags.first()"}"""
        )
        assertEquals(2, wrong.correct)
        assertFalse(wrong.passed)
    }
}
