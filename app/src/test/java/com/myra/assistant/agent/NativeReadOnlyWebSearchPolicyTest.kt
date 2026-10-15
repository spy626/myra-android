package com.myra.assistant.agent

import org.junit.Assert.*
import org.junit.Test

class NativeReadOnlyWebSearchPolicyTest {
    private val google = NativeReadOnlyWebSearchPolicy.GOOGLE_PACKAGE
    private val googleResolution = SearchResolution(SearchDestination.BROWSER,
        "current_google_search_context", BrowserSearchExecutor.CURRENT_GOOGLE_APP, google)

    @Test fun allowsOnlyAvailableGenericFinalSearchInCurrentGoogleApp() {
        val request = BrowserSearchRequest("android ai news")
        assertTrue(NativeReadOnlyWebSearchPolicy.eligible(
            request, googleResolution, google, true))
        assertFalse(NativeReadOnlyWebSearchPolicy.eligible(
            request, googleResolution, google, false))
        assertFalse(NativeReadOnlyWebSearchPolicy.eligible(
            request, googleResolution, "com.android.chrome", true))
        assertFalse(NativeReadOnlyWebSearchPolicy.eligible(
            request, googleResolution.copy(targetPackage = "com.android.chrome"), google, true))
        assertFalse(NativeReadOnlyWebSearchPolicy.eligible(
            request, googleResolution.copy(selectedExecutor = BrowserSearchExecutor.GENERIC_WEB),
            google, true))
    }

    @Test fun explicitChromeYouTubeSecretAndOversizeNeverSwitchToNative() {
        listOf(
            BrowserSearchRequest("android ai news", SearchDestination.BROWSER),
            BrowserSearchRequest("android ai news", SearchDestination.YOUTUBE),
            BrowserSearchRequest("my password is 1234"),
            BrowserSearchRequest("otp confirmation"),
            BrowserSearchRequest("a".repeat(141)),
            BrowserSearchRequest("a\nb")
        ).forEach { request ->
            assertFalse(request.toString(), NativeReadOnlyWebSearchPolicy.eligible(
                request, googleResolution, google, true))
        }
        assertFalse(NativeReadOnlyWebSearchPolicy.eligible(BrowserSearchRequest("latest ai"),
            googleResolution.copy(destination = SearchDestination.YOUTUBE), google, true))
    }

    @Test fun nativeIntentPlanNeverCountsAsSearchVerificationOrNewPermission() {
        assertTrue(NativeReadOnlyWebSearchPolicy.safeQuery("latest android update"))
        assertFalse(NativeReadOnlyWebSearchPolicy.safeQuery("private API key please"))
    }
}
