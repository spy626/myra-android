#!/usr/bin/env python3
"""One-time focused website JSON-contract fix. Abort on changed source anchors."""
from pathlib import Path

BASE = Path('app/src')

def edit(rel, old, new):
    path = BASE / rel
    before = path.read_text(encoding='utf-8')
    count = before.count(old)
    if count != 1:
        raise SystemExit(f'Unsafe patch: {path} expected one anchor, got {count}')
    path.write_text(before.replace(old, new, 1), encoding='utf-8')

website = 'main/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGeneration.kt'
# Unlike a prose-only prompt, JSON object mode plus require_parameters ensures a free endpoint
# actually understands the requested format. Keep every existing zero-price/ZDR guard.
edit(website,
     '.put("stream", false).put("max_tokens", 7_000).put("temperature", 0.2)',
     '.put("stream", false).put("max_tokens", 7_000).put("temperature", 0.2)\n'
     '            .put("response_format", JSONObject().put("type", "json_object"))')
edit(website,
     '.put("allow_fallbacks", false).put("max_price", JSONObject().put("prompt", 0)',
     '.put("allow_fallbacks", false).put("require_parameters", true)\n'
     '                .put("max_price", JSONObject().put("prompt", 0)')
edit(website,
     '''            if (result.request.url.toString() == WorkspaceGroqFree.ENDPOINT)
                "Groq Free HTTP ${result.code}: website fallback refused or quota-limited. " +
                    "No further retry or paid fallback; project files unchanged."
            else WorkspaceFreeAiSuggestion.httpFailure(result.code, result.header("Retry-After"))''',
     '''            if (result.request.url.toString() == WorkspaceGroqFree.ENDPOINT) when (result.code) {
                400 -> "Groq Free HTTP 400: request or output format rejected, not a quota " +
                    "or billing signal. No further retry or paid fallback; project files unchanged."
                429 -> "Groq Free HTTP 429: rate-limited; no further retry or paid fallback. " +
                    "Project files unchanged."
                else -> "Groq Free HTTP ${result.code}: website fallback refused. " +
                    "No further retry or paid fallback; project files unchanged."
            } else WorkspaceFreeAiSuggestion.httpFailure(result.code, result.header("Retry-After"))''')

groq_tests = 'test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGroqFallbackTest.kt'
edit(groq_tests,
     'assertEquals("json_object", body.getJSONObject("response_format").getString("type"))',
     '''assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        val envelope = body.getJSONObject("response_format").getJSONObject("json_schema")
        assertTrue(envelope.getBoolean("strict"))
        val schema = envelope.getJSONObject("schema")
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(listOf("files"), (0 until schema.getJSONArray("required").length()).map {
            schema.getJSONArray("required").getString(it)
        })
        val files = schema.getJSONObject("properties").getJSONObject("files")
        assertFalse(files.getBoolean("additionalProperties"))
        assertEquals(WorkspaceWebsiteGeneration.PATHS.toSet(),
            (0 until files.getJSONArray("required").length()).map {
                files.getJSONArray("required").getString(it)
            }.toSet())
        assertFalse(body.has("reasoning_format")) // Unsupported by Groq GPT-OSS; caused HTTP 400.
        assertFalse(body.getBoolean("include_reasoning"))''')

website_tests = 'test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteGenerationTest.kt'
edit(website_tests,
     '''        assertEquals("openrouter/free", body.getString("model"))
        assertEquals(0, body.getJSONObject("provider").getJSONObject("max_price").getInt("prompt"))''',
     '''        assertEquals("openrouter/free", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertTrue(body.getJSONObject("provider").getBoolean("require_parameters"))
        assertTrue(body.getJSONObject("provider").getBoolean("zdr"))
        assertEquals("deny", body.getJSONObject("provider").getString("data_collection"))
        assertEquals(0, body.getJSONObject("provider").getJSONObject("max_price").getInt("prompt"))''')
# Verify Groq 400 remains a format/request error, never disguised as a quota or a trigger to retry.
edit(website_tests,
     '''    @Test fun providerRequestUsesOnlyFreeRouteWithoutPersonalMemory() {''',
     '''    @Test fun groqHttp400IsNotLabeledAsQuotaAndDoesNotSavePartialFiles() {
        val snapshot = fixture()
        val request = WorkspaceWebsiteGroqFallback.request("gsk_test_key",
            WorkspaceWebsiteGeneration.prepare(snapshot.files, snapshot.tasks, snapshot.projects, "site"))
        val response = okhttp3.Response.Builder().request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1).code(400).message("Bad Request").build()
        val failure = runCatching { WorkspaceWebsiteGeneration.readResponse(response) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("request or output format rejected"))
        assertTrue(failure.message.orEmpty().contains("not a quota"))
        assertTrue(snapshot.files.list("site").isEmpty())
    }

    @Test fun providerRequestUsesOnlyFreeRouteWithoutPersonalMemory() {''')
print('Patched bounded free website JSON route, accurate HTTP 400 diagnosis, regression tests.')
