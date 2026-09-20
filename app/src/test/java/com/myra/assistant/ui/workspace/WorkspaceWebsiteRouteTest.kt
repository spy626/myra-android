package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteRouteTest {
    @Test fun groqOnlyWebsiteUsesSavedGroqFreeKeyWithoutOpenRouter() {
        assertEquals(WorkspaceWebsiteRoute.Provider.GROQ,
            WorkspaceWebsiteRoute.choose("", "gsk_test_key", true))
    }

    @Test fun openRouterOnlyWebsiteUsesOpenRouterWithoutGroq() {
        assertEquals(WorkspaceWebsiteRoute.Provider.OPENROUTER,
            WorkspaceWebsiteRoute.choose("sk-or-test", "", false))
    }

    @Test fun twoKeysKeepOpenRouterPrimaryAndAllowExistingConsentedGroqFallback() {
        assertEquals(WorkspaceWebsiteRoute.Provider.OPENROUTER,
            WorkspaceWebsiteRoute.choose("sk-or-test", "gsk_test_key", true))
        assertTrue(WorkspaceWebsiteGroqFallback.eligible(404, true, true, "gsk_test_key"))
        assertTrue(WorkspaceWebsiteGroqFallback.eligible(429, true, true, "gsk_test_key"))
        assertFalse(WorkspaceWebsiteGroqFallback.eligible(404, false, true, "gsk_test_key"))
    }

    @Test fun savedGroqKeyAloneDoesNotAssumeFreeTierOrZdrAndInvalidKeysAreNotUsed() {
        assertNull(WorkspaceWebsiteRoute.choose("", "gsk_test_key", false))
        assertNull(WorkspaceWebsiteRoute.choose("bad key", "", true))
        assertEquals(WorkspaceWebsiteRoute.Provider.GROQ,
            WorkspaceWebsiteRoute.choose("invalid key", "gsk_test_key", true))
    }

    @Test fun groqOnlyRequestUsesStrictFreeWebsiteJsonWithoutOpenRouterAuthorization() {
        val snapshot = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec", "Build Minicoy", mapOf(
            "index.html" to null, "style.css" to null, "script.js" to null))
        val request = WorkspaceWebsiteGroqFallback.request("gsk_test_key", snapshot)
        assertEquals(WorkspaceGroqFree.ENDPOINT, request.url.toString())
        assertEquals("Bearer gsk_test_key", request.header("Authorization"))
        assertTrue(WorkspaceWebsiteGroqFallback.client.interceptors.none { it is WorkspaceFreeRouteRetry })
    }
}
