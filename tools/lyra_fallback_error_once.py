"""One-shot, fail-closed diagnostic-only patch for LYRA Work free fallback."""
from pathlib import Path

ROOT = Path('app/src/main/java/com/myra/assistant/ui/workspace')
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace')


def replace_exact(path, old, new):
    data = path.read_text(encoding='utf-8')
    assert data.count(old) == 1, f'Unexpected source: {path}; anchor occurrences {data.count(old)}'
    path.write_text(data.replace(old, new), encoding='utf-8')

# Reuse the existing bounded, allowlisted classifier rather than recording provider bodies.
err = ROOT / 'WorkspaceWebsiteProviderError.kt'
replace_exact(err, '''    fun category(body: String): String {''', '''    /** OpenRouter 400 only: allowlisted category; never expose error.message, metadata or source. */
    fun openRouterCategory(body: String): String {
        if (body.length !in 1..8192) return "reason unavailable"
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            ?: return "reason unavailable"
        val code = error.optString("code").take(128).lowercase(Locale.ROOT)
        val message = error.optString("message").take(4096).lowercase(Locale.ROOT)
        return when {
            ("api key" in message || "api_key" in code) &&
                ("invalid" in message || "rejected" in message || "unauthorized" in message) ->
                "key or account access rejected"
            "no endpoints" in message || "no providers" in message ||
                "no eligible" in message || "no available providers" in message ->
                "no eligible endpoint for the saved free/privacy constraints"
            "max_price" in message || "price limit" in message ->
                "zero-price or provider price constraint rejected"
            "zdr" in message || "data policy" in message || "data_collection" in message ->
                "privacy constraint rejected"
            "response_format" in message || "json_schema" in message ||
                "json_object" in message -> "response format rejected"
            "max_completion_tokens" in message || "max_tokens" in message ->
                "output token setting rejected"
            "context" in message && ("length" in message || "large" in message) ->
                "context too large"
            "model" in message && ("not found" in message || "unsupported" in message ||
                "invalid" in message) -> "model rejected"
            else -> "reason unavailable"
        }
    }

    fun category(body: String): String {''')

# Only change the message on a definitive OpenRouter HTTP 400; no change to routing/payload.
site = ROOT / 'WorkspaceWebsiteGeneration.kt'
replace_exact(site, '''            } else WorkspaceFreeAiSuggestion.httpFailure(result.code, result.header("Retry-After"))''', '''            } else if (result.request.url.toString() == WorkspaceFreeAiSuggestion.ENDPOINT &&
                result.code == 400) {
                "OpenRouter Free HTTP 400: " +
                    WorkspaceWebsiteProviderError.openRouterCategory(
                        result.body?.let { result.peekBody(8_193L).string() }.orEmpty()) +
                    "; no automatic retry or paid fallback; project files unchanged."
            } else WorkspaceFreeAiSuggestion.httpFailure(result.code, result.header("Retry-After"))''')

x = ROOT / 'WorkspaceXKiroFree.kt'
replace_exact(x, '''            if (!response.isSuccessful) throw IOException("xKiro Free preflight unavailable; no source sent")''', '''            if (!response.isSuccessful) {
                val stage = if (key == null) "model catalog" else "free quota"
                val access = if (response.code == 401 || response.code == 403)
                    "; key or account access refused" else ""
                throw NoSourcePreflight(
                    "xKiro Free $stage preflight HTTP ${response.code}$access; no source sent to xKiro")
            }''')
replace_exact(x, '''        if (open != null) {
            if (chain.call().isCanceled()) throw IOException("Work request cancelled; no fallback sent")
            val second = chain.proceed(open) // Any network ambiguity ends the chain.
            if (!WorkspaceCodingAutoFallback.openRouterRejected(second.code)) return second
            second.close()
        }
        val groq = requestForGroq() ?: throw IOException(
            "$reason; no eligible Groq Free/ZDR route remains; files unchanged")''', '''        var openRouterResult = if (WorkspaceCodingAutoFallback.validKey(openKey))
            "OpenRouter Free request could not be prepared"
        else "OpenRouter Free key missing or invalid"
        if (open != null) {
            if (chain.call().isCanceled()) throw IOException("Work request cancelled; no fallback sent")
            val second = chain.proceed(open) // Any network ambiguity ends the chain.
            if (!WorkspaceCodingAutoFallback.openRouterRejected(second.code)) return second
            openRouterResult = "OpenRouter Free HTTP ${second.code}"
            second.close()
        }
        val groq = requestForGroq() ?: throw IOException(
            "$reason; $openRouterResult; no eligible Groq Free/ZDR route remains; files unchanged")''')

new_test = TEST / 'WorkspaceWebsiteProviderErrorTest.kt'
assert not new_test.exists(), 'Test already exists; fail closed'
new_test.write_text('''package com.myra.assistant.ui.workspace

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
''', encoding='utf-8')

# Exercise actual website HTTP response path, not only the category helper.
site_test = TEST / 'WorkspaceWebsiteGenerationTest.kt'
replace_exact(site_test, '''import org.json.JSONObject
''', '''import org.json.JSONObject
import okhttp3.ResponseBody.Companion.toResponseBody
''')
replace_exact(site_test, '''    @Test fun providerRequestUsesOnlyFreeRouteWithoutPersonalMemory() {''', '''    @Test fun openRouter400ReportsSafeCategoryAndNeverWritesOrRetries() {
        val s = fixture()
        val snapshot = WorkspaceWebsiteGeneration.prepare(s.files, s.tasks, s.projects, "site")
        val request = WorkspaceWebsiteGeneration.request("safe_test_key", snapshot)
        val raw = """{"error":{"message":"response_format unsupported SECRET_DO_NOT_ECHO"}}""
        val response = okhttp3.Response.Builder().request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1).code(400).message("Bad Request")
            .body(raw.toResponseBody()).build()
        val failure = runCatching { WorkspaceWebsiteGeneration.readResponse(response) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("OpenRouter Free HTTP 400: response format rejected"))
        assertFalse(failure.message.orEmpty().contains("SECRET_DO_NOT_ECHO"))
        assertTrue(s.files.list("site").isEmpty())
    }

    @Test fun providerRequestUsesOnlyFreeRouteWithoutPersonalMemory() {''')

print('Diagnostic-only Work fallback patch applied; 4 production/test files + 1 new test.')