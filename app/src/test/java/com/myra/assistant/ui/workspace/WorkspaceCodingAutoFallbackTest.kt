package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceCodingAutoFallbackTest {
    private val p = WorkspaceCodingAutoFallback
    @Test fun optInAndGroqFreeZdrAreIndependent() {
        assertFalse(p.permitted(false, true))
        assertFalse(p.permitted(true, false))
        assertTrue(p.permitted(true, true))
        assertFalse(p.groqPermitted(true, false, "gsk-test"))
        assertFalse(p.groqPermitted(false, true, "gsk-test"))
        assertTrue(p.groqPermitted(true, true, "gsk-test"))
        assertFalse(p.validKey("key,another"))
    }
    @Test fun onlyDefinitiveUnavailableHttpStatusesQualify() {
        for (s in listOf(429, 502, 503, 504)) {
            assertTrue(p.xKiroRejected(s)); assertTrue(p.openRouterRejected(s))
        }
        assertTrue(p.openRouterRejected(404))
        for (s in listOf(200, 400, 401, 402, 403, 413)) {
            assertFalse(p.xKiroRejected(s)); assertFalse(p.openRouterRejected(s))
        }
    }
    @Test fun displayRealFallbackOnly() {
        assertEquals("OpenRouter Free", p.displayName(WorkspaceFreeAiSuggestion.ENDPOINT))
        assertEquals("Groq Free", p.displayName(WorkspaceGroqFree.ENDPOINT))
        assertNull(p.displayName(WorkspaceXKiroFree.ENDPOINT))
    }
}
