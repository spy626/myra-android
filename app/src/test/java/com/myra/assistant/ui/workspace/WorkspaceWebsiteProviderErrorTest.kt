package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteProviderErrorTest {
    @Test fun openRouter400ClassifiesOnlyKnownCategoriesWithoutEchoingSource() {
        val secret = "PRIVATE_SENTINEL_19372"
        assertEquals("response format rejected", WorkspaceWebsiteProviderError.openRouterCategory(
            """{"error":{"message":"response_format json_object unsupported $secret"}}"""))
        assertEquals("no eligible endpoint for the saved free/privacy constraints",
            WorkspaceWebsiteProviderError.openRouterCategory(
                """{"error":{"message":"No endpoints found matching ZDR $secret"}}"""))
        assertEquals("key or account access rejected", WorkspaceWebsiteProviderError.openRouterCategory(
            """{"error":{"message":"Invalid API key $secret"}}"""))
        assertEquals("reason unavailable", WorkspaceWebsiteProviderError.openRouterCategory(
            """{"error":{"message":"$secret"}}"""))
        assertEquals("reason unavailable", WorkspaceWebsiteProviderError.openRouterCategory(
            "<html>$secret</html>"))
        assertFalse(WorkspaceWebsiteProviderError.openRouterCategory(
            """{"error":{"message":"response_format $secret"}}""").contains(secret))
    }

    @Test fun openRouter400DoesNotConfuseQuotaOrPricingWithKeyFailure() {
        assertEquals("zero-price or provider price constraint rejected",
            WorkspaceWebsiteProviderError.openRouterCategory(
                """{"error":{"message":"max_price filtering refused"}}"""))
        assertEquals("reason unavailable", WorkspaceWebsiteProviderError.openRouterCategory(
            """{"error":{"message":"quota may be exhausted"}}"""))
    }

    @Test fun openRouter429DoesNotClaimDailyQuotaFromStatusOrGenericRateLimit() {
        val secret = "PRIVATE_SENTINEL_429"
        val generic = """{"error":{"code":429,"message":"Rate limit exceeded $secret"}}""
        val result = WorkspaceWebsiteProviderError.openRouter429Summary(generic, null)
        assertTrue(result.contains("HTTP 429: rate-limited; scope unverified"))
        assertTrue(result.contains("does not independently prove"))
        assertFalse(result.contains(secret))
        assertEquals("rate-limited; scope unverified",
            WorkspaceWebsiteProviderError.openRouter429Category("<html>$secret</html>"))
        assertEquals("rate-limited; scope unverified",
            WorkspaceWebsiteProviderError.openRouter429Category("x".repeat(8193)))
    }

    @Test fun openRouter429OnlyReportsExplicitProviderScopeAndBoundedRetryAfter() {
        val secret = "SECRET_SOURCE_43"
        val daily = """{"error":{"message":"Daily request limit exceeded $secret"}}""
        assertEquals("provider reports a daily limit for this route",
            WorkspaceWebsiteProviderError.openRouter429Category(daily))
        val minute = """{"error":{"message":"Requests per minute limit exceeded $secret"}}""
        assertEquals("provider reports a per-minute limit for this route",
            WorkspaceWebsiteProviderError.openRouter429Category(minute))
        val provider = """{"error":{"message":"Upstream provider rate limit $secret"}}""
        assertEquals("provider-side rate or capacity limit reported",
            WorkspaceWebsiteProviderError.openRouter429Category(provider))
        val good = WorkspaceWebsiteProviderError.openRouter429Summary(minute, "120")
        assertTrue(good.contains("Provider suggests waiting 120 seconds"))
        assertFalse(good.contains(secret))
        val malicious = WorkspaceWebsiteProviderError.openRouter429Summary(minute, "120\n$secret")
        assertFalse(malicious.contains("Provider suggests waiting"))
        assertFalse(malicious.contains(secret))
        assertFalse(WorkspaceWebsiteProviderError.openRouter429Summary("", "999999")
            .contains("Provider suggests waiting"))
    }
}
