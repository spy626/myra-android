package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceOpenRouterKeyPreflightTest {
    private val preflight = WorkspaceOpenRouterKeyPreflight

    @Test fun validKeyResponseRequiresExpectedEnvelope() {
        assertEquals(WorkspaceOpenRouterKeyPreflight.Result.VERIFIED,
            preflight.classify(200, """{"data":{"is_free_tier":true,"usage":0}}"""))
        assertEquals(WorkspaceOpenRouterKeyPreflight.Result.UNVERIFIED,
            preflight.classify(200, """{"error":{"message":"PRIVATE_KEY_MUST_NOT_APPEAR"}}"""))
        assertEquals(WorkspaceOpenRouterKeyPreflight.Result.UNVERIFIED,
            preflight.classify(200, "<html>not an API response</html>"))
    }

    @Test fun onlyAuthenticationRejectionProvesAccessRefused() {
        assertEquals(WorkspaceOpenRouterKeyPreflight.Result.ACCESS_REFUSED, preflight.classify(401))
        assertEquals(WorkspaceOpenRouterKeyPreflight.Result.ACCESS_REFUSED, preflight.classify(403))
        for (code in listOf(400, 402, 408, 429, 500, 503)) {
            assertEquals(WorkspaceOpenRouterKeyPreflight.Result.UNVERIFIED,
                preflight.classify(code, """{"error":{"message":"PRIVATE_KEY_MUST_NOT_APPEAR"}}"""))
        }
    }
}
