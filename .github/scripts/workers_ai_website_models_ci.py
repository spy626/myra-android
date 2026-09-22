#!/usr/bin/env python3
"""Temporary CI patch; never embeds keys or sends source to an external AI."""
from pathlib import Path

BASE = Path('app/src/main')

def apply(relative, edits):
    p = BASE / relative
    content = p.read_text(encoding='utf-8')
    for old, new in edits:
        occurrences = content.count(old)
        if occurrences != 1:
            raise RuntimeError(f'{relative}: expected one anchor, got {occurrences}: {old[:75]!r}')
        content = content.replace(old, new, 1)
    p.write_text(content, encoding='utf-8')

cloud = 'java/com/myra/assistant/ui/workspace/WorkspaceCloudflareFree.kt'
apply(cloud, [
('''    const val MODEL = "@cf/zai-org/glm-4.7-flash"
    private const val ROOT = "https://api.cloudflare.com/client/v4/accounts/"
    private const val PATH = "/ai/run/@cf/zai-org/glm-4.7-flash"''', '''    const val MODEL = "@cf/zai-org/glm-4.7-flash"
    const val MODEL_PREFERENCE_KEY = "workspace_cloudflare_selected_free_model"
    val MODELS = listOf(MODEL, "@cf/qwen/qwen3-30b-a3b-fp8",
        "@cf/google/gemma-4-26b-a4b-it", "@cf/nvidia/nemotron-3-120b-a12b")
    val MODEL_LABELS = listOf("GLM-4.7-Flash", "Qwen3 30B", "Gemma 4 26B", "Nemotron 3 120B")
    fun chosenModel(saved: String?): String = saved?.takeIf { it in MODELS } ?: MODEL
    private const val ROOT = "https://api.cloudflare.com/client/v4/accounts/"'''),
('''    fun endpoint(accountId: String): String {
        require(validAccountId(accountId)) { "Enter the 32-character Cloudflare Account ID in API Settings" }
        return ROOT + accountId.lowercase() + PATH
    }''', '''    fun endpoint(accountId: String, model: String = MODEL): String {
        require(validAccountId(accountId)) { "Enter the 32-character Cloudflare Account ID in API Settings" }
        require(model in MODELS) { "Cloudflare model is not approved for this Free-only selector" }
        return ROOT + accountId.lowercase() + "/ai/run/" + model
    }'''),
('''        .callTimeout(180, TimeUnit.SECONDS)
        .build()''', '''        // Stream website generation incrementally; a stalled stream still fails safely.
        // The 300s ceiling is total, NOT an unlimited inference or a retry policy.
        .callTimeout(300, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()'''),
('''    private fun request(token: String, accountId: String, payload: JSONObject,
                        maxCompletion: Int): Request {
        require(validToken(token)) { "Save a valid Cloudflare Workers AI token in API Settings" }
        val url = endpoint(accountId)''', '''    private fun request(token: String, accountId: String, payload: JSONObject,
                        maxCompletion: Int, model: String = MODEL, website: Boolean = false): Request {
        require(validToken(token)) { "Save a valid Cloudflare Workers AI token in API Settings" }
        val url = endpoint(accountId, model)'''),
('''        // Fixed Cloudflare-hosted Workers AI model, never Gateway's model router.
        payload.remove("model")''', '''        // Exact user-selected, allowlisted Cloudflare-hosted model; never AI Gateway.
        payload.remove("model")'''),
('''        payload.remove("max_tokens")
        // GLM may spend the entire completion budget on non-visible reasoning, leaving
        // content empty even on HTTP 200. Both switches are documented Workers AI inputs.
        // Disable thinking before generation; never display reasoning as a substitute reply.
        payload.put("stream", false).put("max_completion_tokens", maxCompletion)
            .put("store", false).put("reasoning_effort", JSONObject.NULL)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))''', '''        payload.remove("max_tokens")
        payload.remove("reasoning_effort")
        payload.remove("chat_template_kwargs")
        payload.remove("store")
        payload.put("stream", website)
        if (model == MODELS[1]) {
            // Qwen's native Workers AI schema uses max_tokens, not max_completion_tokens.
            payload.put("max_tokens", maxCompletion)
        } else {
            payload.put("max_completion_tokens", maxCompletion).put("store", false)
            if (model == MODEL) {
                // GLM-specific documented switch: prevent invisible reasoning consuming
                // the whole output budget; do not send this to other model schemas.
                payload.put("reasoning_effort", JSONObject.NULL)
                    .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            }
        }'''),
('''                    messages: List<WorkspaceConversationStore.Message>): Request {''', '''                    messages: List<WorkspaceConversationStore.Message>,
                    model: String = MODEL): Request {'''),
('''        return request(token, accountId, payload, 2_048)''', '''        return request(token, accountId, payload, 2_048, model)'''),
('''                       snapshot: WorkspaceWebsiteGeneration.Snapshot): Request {''', '''                       snapshot: WorkspaceWebsiteGeneration.Snapshot,
                       model: String = MODEL): Request {'''),
('''        return request(token, accountId, JSONObject(buffer.readUtf8()), 7_000)''', '''        return request(token, accountId, JSONObject(buffer.readUtf8()), 7_000, model, website = true)'''),
('''    fun editRequest(token: String, accountId: String, prompt: String): Request {''', '''    fun editRequest(token: String, accountId: String, prompt: String,
                    model: String = MODEL): Request {'''),
('''            JSONObject(WorkspaceChatGateway.openRouterBody(selected)), 3_500)''', '''            JSONObject(WorkspaceChatGateway.openRouterBody(selected)), 3_500, model)'''),
('''    private fun readText(response: Response, maxChars: Int): String = response.use { result ->
        require(result.request.url.host == "api.cloudflare.com" &&
            result.request.url.pathSegments.takeLast(5) ==
                listOf("ai", "run", "@cf", "zai-org", "glm-4.7-flash")) {
            "Cloudflare response arrived from an unexpected route; nothing saved"
        }''', '''    private fun assertApprovedRoute(response: Response) {
        val segments = response.request.url.pathSegments
        require(response.request.url.host == "api.cloudflare.com" &&
            segments.takeLast(5).take(2) == listOf("ai", "run") &&
            segments.takeLast(3).joinToString("/") in MODELS) {
            "Cloudflare response arrived from an unexpected route; nothing saved"
        }
    }

    private fun readText(response: Response, maxChars: Int): String = response.use { result ->
        assertApprovedRoute(result)'''),
('''    fun readWebsite(response: Response): Map<String, String> =
        WorkspaceWebsiteGeneration.parse(readText(response, 30_000))''', '''    /** Website-only SSE: bounded incremental bytes, no partial writes or uncertain retries.
     * Read and validate the COMPLETE finish event before invoking the existing local validator.
     * Both the SSE and synchronous API response shape are supported for compatibility.
     */
    fun readWebsite(response: Response): Map<String, String> {
        if (!response.isSuccessful ||
            !response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)) {
            return WorkspaceWebsiteGeneration.parse(readText(response, 30_000))
        }
        return response.use { result ->
            assertApprovedRoute(result)
            val source = result.body?.source()
                ?: throw IllegalArgumentException("Cloudflare website stream missing; no files changed")
            val text = StringBuilder()
            var totalChars = 0
            var done = false
            var sawChoice = false
            var completed = false
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    totalChars += line.length
                    require(totalChars <= MAX_BYTES) { "Cloudflare website stream oversized; no files changed" }
                    if (!line.startsWith("data:")) continue
                    val data = line.substringAfter("data:").trim()
                    if (data == "[DONE]") { done = true; break }
                    if (data.isBlank()) continue
                    val event = runCatching { JSONObject(data) }
                        .getOrElse { throw IllegalArgumentException("Cloudflare website stream invalid JSON; no files changed") }
                    require(!event.has("error") && (!event.has("success") || event.optBoolean("success"))) {
                        "Cloudflare website stream refused; no files changed"
                    }
                    val body = event.optJSONObject("result") ?: event
                    val choice = body.optJSONArray("choices")?.optJSONObject(0)
                    val piece = if (choice != null) {
                        sawChoice = true
                        val finish = choice.optString("finish_reason")
                        if (finish.isNotBlank() && finish != "null") {
                            require(finish == "stop") {
                                "Cloudflare website output incomplete [format: output_limit_or_stop]; no files changed"
                            }
                            completed = true
                        }
                        choice.optJSONObject("delta")?.opt("content")
                    } else body.opt("response")
                    if (piece != null && piece != JSONObject.NULL) {
                        val chunk = visibleText(piece)
                            ?: throw IllegalArgumentException("Cloudflare website stream has unsupported text; no files changed")
                        text.append(chunk)
                        require(text.length <= 30_000) { "Cloudflare website output oversized; no files changed" }
                    }
                }
            } catch (e: java.io.IOException) {
                throw IllegalStateException("Cloudflare website stream interrupted or stalled; outcome uncertain. No files changed or automatic resend.")
            }
            require(done && (!sawChoice || completed) && text.isNotBlank()) {
                "Cloudflare website stream ended before a complete reply; no files changed or automatic resend"
            }
            WorkspaceWebsiteGeneration.parse(text.toString())
        }
    }''')
])

apply('java/com/myra/assistant/ui/workspace/WorkspaceChatGateway.kt', [
('''                image: Image? = null, cloudflareAccountId: String = ""): Request {''', '''                image: Image? = null, cloudflareAccountId: String = "",
                cloudflareModel: String = WorkspaceCloudflareFree.MODEL): Request {'''),
('''            return WorkspaceCloudflareFree.chatRequest(key, cloudflareAccountId, messages)''', '''            return WorkspaceCloudflareFree.chatRequest(key, cloudflareAccountId, messages, cloudflareModel)''')
])

apply('java/com/myra/assistant/ui/workspace/WorkspaceActivity.kt', [
('''                keys.get(ApiKeyStore.CLOUDFLARE_ACCOUNT) else "") }''', '''                keys.get(ApiKeyStore.CLOUDFLARE_ACCOUNT) else "",
            WorkspaceCloudflareFree.chosenModel(preferences.getString(
                WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY, WorkspaceCloudflareFree.MODEL))) }''')
])

apply('java/com/myra/assistant/ui/workspace/WorkspaceChatCodingFlow.kt', [
('''                    WorkspaceCloudflareFree.websiteRequest(cloudKey, cloudAccount, snapshot)''', '''                    WorkspaceCloudflareFree.websiteRequest(cloudKey, cloudAccount, snapshot,
                        WorkspaceCloudflareFree.chosenModel(activity.getSharedPreferences(
                            "workspace_ui", Context.MODE_PRIVATE).getString(
                            WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY, WorkspaceCloudflareFree.MODEL)))'''),
('''                else if (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException)
                    "Website provider timed out. No incomplete code was saved."''', '''                else if (primary == WorkspaceWebsiteRoute.Provider.CLOUDFLARE &&
                    (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException))
                    "Cloudflare website stream timed out or stalled; server outcome is uncertain. " +
                        "No files changed or automatic retry. Check your daily neurons before manually trying another Free model."
                else if (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException)
                    "Website provider timed out. No incomplete code was saved."'''),
('''            WorkspaceCloudflareFree.editRequest(key, cloudAccount, prepared.prompt)''', '''            WorkspaceCloudflareFree.editRequest(key, cloudAccount, prepared.prompt,
                WorkspaceCloudflareFree.chosenModel(activity.getSharedPreferences(
                    "workspace_ui", Context.MODE_PRIVATE).getString(
                    WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY, WorkspaceCloudflareFree.MODEL)))''')
])

apply('java/com/myra/assistant/ui/settings/ApiCloudSettingsActivity.kt', [
('''import android.widget.Toast''', '''import android.widget.ArrayAdapter
import android.widget.Toast'''),
('''        b.cloudflareAccountId.setText(keys.get(ApiKeyStore.CLOUDFLARE_ACCOUNT))''', '''        b.cloudflareAccountId.setText(keys.get(ApiKeyStore.CLOUDFLARE_ACCOUNT))'''),
('''        val workspacePrefs = getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
        b.advancedProviderControls.visibility''', '''        val workspacePrefs = getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
        b.cloudflareModelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            WorkspaceCloudflareFree.MODEL_LABELS).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val selectedModel = WorkspaceCloudflareFree.chosenModel(workspacePrefs.getString(
            WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY, WorkspaceCloudflareFree.MODEL))
        b.cloudflareModelSpinner.setSelection(WorkspaceCloudflareFree.MODELS.indexOf(selectedModel))
        b.advancedProviderControls.visibility'''),
('''            keys.put(ApiKeyStore.CLOUDFLARE_ACCOUNT, b.cloudflareAccountId.text.toString())''', '''            keys.put(ApiKeyStore.CLOUDFLARE_ACCOUNT, b.cloudflareAccountId.text.toString())
            workspacePrefs.edit().putString(WorkspaceCloudflareFree.MODEL_PREFERENCE_KEY,
                WorkspaceCloudflareFree.MODELS[b.cloudflareModelSpinner.selectedItemPosition]).apply()''')
])

apply('res/layout/activity_api_cloud_settings.xml', [
('''    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="9dp" android:text="Save both values, then enable Cloudflare in Advanced.''', '''    <TextView style="@style/MyraLabel" android:text="CLOUDFLARE FREE MODEL · CHAT &amp; WORK"/>
    <Spinner android:id="@+id/cloudflareModelSpinner" android:layout_width="match_parent" android:layout_height="52dp" android:backgroundTint="#B9DEBF" android:contentDescription="Choose one Cloudflare Workers Free model"/>
    <TextView android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginBottom="9dp" android:text="Select GLM, Qwen3, Gemma 4 or Nemotron, then Save. Switching models is MANUAL; all share your account's 10,000 free neurons/day. No automatic retry after a timeout or incomplete website. Save both values, then enable Cloudflare in Advanced.'''),
('''directly to Cloudflare GLM-4.7-Flash, never AI Gateway or a paid model.''', '''directly to your selected allowlisted Cloudflare-hosted model, never AI Gateway or a paid model.''')
])
print('SCOPED_PATCH_APPLIED')
