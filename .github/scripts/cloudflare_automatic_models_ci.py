#!/usr/bin/env python3
"""One-shot, anchored, fail-closed changes: Cloudflare same-provider model capacity fallback."""
from pathlib import Path

ROOT = Path('app/src/main')
CF = ROOT / 'java/com/myra/assistant/ui/workspace/WorkspaceCloudflareFree.kt'
SETTINGS = ROOT / 'java/com/myra/assistant/ui/settings/ApiCloudSettingsActivity.kt'
LAYOUT = ROOT / 'res/layout/activity_api_cloud_settings.xml'
ACTIVITY = ROOT / 'java/com/myra/assistant/ui/workspace/WorkspaceActivity.kt'
FLOW = ROOT / 'java/com/myra/assistant/ui/workspace/WorkspaceChatCodingFlow.kt'
TEST = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceCloudflareModelsWebsiteTest.kt')

def swap(path, old, new):
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    assert count == 1, f'{path}: expected exactly one patch anchor, found {count}: {old[:65]!r}'
    path.write_text(text.replace(old, new), encoding='utf-8')

swap(CF, 'import okhttp3.OkHttpClient\n', 'import okhttp3.OkHttpClient\nimport okhttp3.Interceptor\n')
swap(CF, '/** Explicitly selected Workers Free inference. Never uses AI Gateway, dynamic routes or paid models.',
     '/** Approved Workers Free inference. Same-account model fallback only for definitive model-level rejections.')
swap(CF, '    // Independent client; existing OpenRouter/Groq retry and memory interceptors never run.\n    val client: OkHttpClient = OkHttpClient.Builder()\n', '''    /** Only these documented Cloudflare error codes prove the selected model was unavailable.
     * 3036 is the SHARED account quota: absolutely no model switch on 3036 or unknown 429.
     * A timeout, partial HTTP 200, invalid answer or interruption never enters this method.
     */
    internal fun nextModelAfter(response: Response, current: String): String? {
        if (current !in MODELS || response.isSuccessful) return null
        val code = runCatching {
            JSONObject(response.peekBody(8_193L).string()).optJSONArray("errors")
                ?.optJSONObject(0)?.optInt("code", -1)
        }.getOrNull() ?: return null
        val definiteModelRejection = (response.code == 429 && code == 3040) ||
            (response.code == 404 && code == 3042) ||
            (response.code == 400 && code == 5007)
        return if (definiteModelRejection) MODELS[(MODELS.indexOf(current) + 1) % MODELS.size] else null
    }

    /** Preserve the exact approved text/source while adapting ONLY the next model's schema.
     * Rebuild the POST body because Qwen requires max_tokens, unlike the other models.
     */
    internal fun alternateRequest(original: Request, nextModel: String): Request {
        val url = original.url
        val segments = url.pathSegments
        require(url.scheme == "https" && url.host == "api.cloudflare.com" && url.port == 443 &&
            url.query == null && segments.size == 9 &&
            segments.take(3) == listOf("client", "v4", "accounts") &&
            segments[4] == "ai" && segments[5] == "run") {
            "Cloudflare automatic model switch refused an unexpected route"
        }
        val previous = segments.drop(6).joinToString("/")
        require(previous in MODELS && nextModel in MODELS && previous != nextModel) {
            "Cloudflare automatic model switch refused an unapproved model"
        }
        val originalBody = requireNotNull(original.body) { "Cloudflare model switch requires the same POST body" }
        val buffer = Buffer()
        originalBody.writeTo(buffer)
        require(buffer.size in 1..200_000) { "Cloudflare model switch body is missing or oversized" }
        val payload = JSONObject(buffer.readUtf8())
        val budget = if (payload.has("max_tokens")) payload.optInt("max_tokens", -1)
            else payload.optInt("max_completion_tokens", -1)
        require(budget in 1..7_000 && payload.optJSONArray("messages") != null) {
            "Cloudflare model switch has an invalid output budget or messages"
        }
        payload.remove("max_tokens")
        payload.remove("max_completion_tokens")
        payload.remove("store")
        payload.remove("reasoning_effort")
        payload.remove("chat_template_kwargs")
        if (nextModel == MODELS[1]) payload.put("max_tokens", budget)
        else {
            payload.put("max_completion_tokens", budget).put("store", false)
            if (nextModel == MODEL) payload.put("reasoning_effort", JSONObject.NULL)
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        }
        return original.newBuilder().url(endpoint(segments[3], nextModel))
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    /** Maximum four allowlisted models, exactly once each, only after definite rejection.
     * A successful response, including an incomplete SSE body, belongs to the original model.
     * Network errors are propagated; there is no uncertain retry or paid-provider escape.
     */
    private class AutomaticFreeModelFallback : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            val segments = request.url.pathSegments
            val initial = segments.drop(6).joinToString("/")
            require(segments.size == 9 && initial in MODELS) {
                "Cloudflare automatic request has an unapproved model route"
            }
            var current = initial
            var attempts = 1
            while (true) {
                val response = chain.proceed(request)
                val next = if (attempts < MODELS.size) nextModelAfter(response, current) else null
                if (next == null) return response
                val alternate = runCatching { alternateRequest(request, next) }
                    .getOrElse { return response }
                response.close()
                request = alternate
                current = next
                attempts++
            }
        }
    }

    // Separate client; no OpenRouter/Groq interceptors, AI Gateway or provider switching.
    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AutomaticFreeModelFallback())
''')
swap(SETTINGS, 'import android.widget.ArrayAdapter\n', '')
swap(SETTINGS, '''        b.cloudflareModelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            WorkspaceCloudflareFree.MODEL_LABELS).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selectedModel = WorkspaceCloudflareFree.chosenModel(workspacePrefs.getString(
            WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY, WorkspaceCloudflareFree.MODEL))
        b.cloudflareModelSpinner.setSelection(WorkspaceCloudflareFree.MODELS.indexOf(selectedModel))
''', '')
swap(SETTINGS, '''            workspacePrefs.edit().putString(WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY,
                WorkspaceCloudflareFree.MODELS[b.cloudflareModelSpinner.selectedItemPosition]).apply()
''', '')
swap(LAYOUT, '''    <TextView style="@style/MyraLabel" android:text="CLOUDFLARE FREE MODEL · CHAT &amp; WORK"/>
    <Spinner android:id="@+id/cloudflareModelSpinner" android:layout_width="match_parent" android:layout_height="52dp" android:backgroundTint="#B9DEBF" android:contentDescription="Choose one Cloudflare Workers Free model"/>
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="9dp" android:text="Select GLM, Qwen3, Gemma 4 or Nemotron, then Save. Switching models is MANUAL; all share your account's 10,000 free neurons/day. No automatic retry after a timeout or incomplete website. Save both values, then enable Cloudflare in Advanced. The API token stays encrypted on this phone; never paste it in Chat. No separate test app is needed." android:textColor="#AAA5AA" android:textSize="12sp"/>
''', '''    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="9dp" android:text="Automatic Cloudflare Free models: GLM, Qwen3, Gemma 4 and Nemotron. The same selected text/source may switch to the next free model only after a definite model-capacity or missing-model rejection. All share one account's 10,000 neurons/day: when daily quota runs out, all stop. No resend after timeout, uncertain 429, or incomplete output; no paid route or cross-provider switch. Save token and Account ID, then enable Cloudflare in Advanced. Keep the token private." android:textColor="#AAA5AA" android:textSize="12sp"/>
''')
swap(LAYOUT, 'directly to your selected allowlisted Cloudflare-hosted model, never AI Gateway or a paid model.',
     'to allowlisted Cloudflare Free models only, with same-provider automatic switching solely after definitive capacity or missing-model rejection; never AI Gateway, a paid model, or another provider.')
swap(ACTIVITY, '''            if (provider == WorkspaceChatGateway.Provider.CLOUDFLARE_FREE)
                keys.get(ApiKeyStore.CLOUDFLARE_ACCOUNT) else "",
            WorkspaceCloudflareFree.chosenModel(preferences.getString(
                WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY, WorkspaceCloudflareFree.MODEL))) }''', '''            if (provider == WorkspaceChatGateway.Provider.CLOUDFLARE_FREE)
                keys.get(ApiKeyStore.CLOUDFLARE_ACCOUNT) else "") }''')
swap(FLOW, '''                WorkspaceWebsiteRoute.Provider.CLOUDFLARE ->
                    WorkspaceCloudflareFree.websiteRequest(cloudKey, cloudAccount, snapshot,
                        WorkspaceCloudflareFree.chosenModel(activity.getSharedPreferences(
                            "workspace_ui", Context.MODE_PRIVATE).getString(
                            WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY, WorkspaceCloudflareFree.MODEL)))''', '''                WorkspaceWebsiteRoute.Provider.CLOUDFLARE ->
                    WorkspaceCloudflareFree.websiteRequest(cloudKey, cloudAccount, snapshot)''')
swap(FLOW, '''            WorkspaceCloudflareFree.editRequest(key, cloudAccount, prepared.prompt,
                WorkspaceCloudflareFree.chosenModel(activity.getSharedPreferences(
                    "workspace_ui", Context.MODE_PRIVATE).getString(
                    WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY, WorkspaceCloudflareFree.MODEL)))''', '''            WorkspaceCloudflareFree.editRequest(key, cloudAccount, prepared.prompt)''')

extra = '''
    @Test fun onlyDefinitiveModelFailuresAllowAutomaticSwitchNotSharedQuota() {
        val request = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot)
        fun failed(http: Int, code: Int): Response = Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(http).message("Rejected")
            .body(JSONObject().put("errors", JSONArray().put(JSONObject().put("code", code)))
                .toString().toResponseBody()).build()
        assertEquals(WorkspaceCloudflareFree.MODELS[1],
            WorkspaceCloudflareFree.nextModelAfter(failed(429, 3040), WorkspaceCloudflareFree.MODEL))
        assertEquals(WorkspaceCloudflareFree.MODELS[1],
            WorkspaceCloudflareFree.nextModelAfter(failed(404, 3042), WorkspaceCloudflareFree.MODEL))
        assertEquals(WorkspaceCloudflareFree.MODELS[1],
            WorkspaceCloudflareFree.nextModelAfter(failed(400, 5007), WorkspaceCloudflareFree.MODEL))
        for ((http, code) in listOf(429 to 3036, 429 to 0, 403 to 5035, 408 to 3007,
            400 to 5004, 401 to 0, 503 to 0)) {
            assertNull("Must stop on HTTP $http error $code",
                WorkspaceCloudflareFree.nextModelAfter(failed(http, code), WorkspaceCloudflareFree.MODEL))
        }
        assertNull(WorkspaceCloudflareFree.nextModelAfter(response(request, files(), "application/json"),
            WorkspaceCloudflareFree.MODEL))
    }

    @Test fun automaticFallbackPreservesExactMessagesAndAdaptsAllFourModelSchemas() {
        val original = WorkspaceCloudflareFree.websiteRequest(token, account, snapshot)
        fun json(req: Request): JSONObject = Buffer().also {
            requireNotNull(req.body).writeTo(it)
        }.let { JSONObject(it.readUtf8()) }
        var request = original
        val originalBody = json(original)
        WorkspaceCloudflareFree.MODELS.drop(1).forEach { next ->
            request = WorkspaceCloudflareFree.alternateRequest(request, next)
            assertEquals("api.cloudflare.com", request.url.host)
            assertTrue(request.url.toString().endsWith("/ai/run/$next"))
            assertEquals(original.header("Authorization"), request.header("Authorization"))
            val body = json(request)
            assertEquals(originalBody.getJSONArray("messages").toString(),
                body.getJSONArray("messages").toString())
            assertEquals(originalBody.getBoolean("stream"), body.getBoolean("stream"))
            assertFalse(body.has("provider"))
            assertFalse(body.has("model"))
            if (next == WorkspaceCloudflareFree.MODELS[1]) {
                assertEquals(7000, body.getInt("max_tokens"))
                assertFalse(body.has("max_completion_tokens"))
                assertFalse(body.has("store"))
            } else {
                assertEquals(7000, body.getInt("max_completion_tokens"))
                assertFalse(body.has("max_tokens"))
                assertFalse(body.has("chat_template_kwargs"))
            }
        }
        assertTrue(runCatching { WorkspaceCloudflareFree.alternateRequest(
            original, "@cf/unknown/paid-model") }.isFailure)
        val nonCloudflare = original.newBuilder().url("https://example.org/api").build()
        assertTrue(runCatching { WorkspaceCloudflareFree.alternateRequest(
            nonCloudflare, WorkspaceCloudflareFree.MODELS[1]) }.isFailure)
        assertFalse(WorkspaceCloudflareFree.client.retryOnConnectionFailure)
        assertFalse(WorkspaceCloudflareFree.client.followRedirects)
    }
'''
text = TEST.read_text(encoding='utf-8')
assert text.rstrip().endswith('}') and text.count('class WorkspaceCloudflareModelsWebsiteTest {') == 1
assert 'onlyDefinitiveModelFailuresAllowAutomaticSwitchNotSharedQuota' not in text
TEST.write_text(text.rstrip()[:-1] + extra + '}\n', encoding='utf-8')
print('AUTOMATIC_CLOUDFLARE_MODELS_SCOPED_PATCH_STAGED')
