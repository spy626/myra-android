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
}
