package com.myra.assistant.ui.workspace

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** Optional, user-approved xKiro Free route for Work coding ONLY. No automatic fallback.
 * Public catalog + key-specific free quota are checked before EVERY source-bearing POST;
 * missing/ambiguous metadata fails closed. A :free label alone is not sufficient.
 */
internal object WorkspaceXKiroFree {
    const val PREFERENCE_KEY = "workspace_xkiro_work_opt_in"
    const val MODEL = "qwen/qwen3-coder-plus:free"
    const val ENDPOINT = "https://api.xkiro.com/v1/chat/completions"
    private const val MODELS = "https://api.xkiro.com/v1/models"
    private const val USAGE = "https://api.xkiro.com/v1/usage"
    private const val WEBSITE_OUTPUT_TOKENS = 7_000
    private const val EDIT_OUTPUT_TOKENS = 3_500
    private const val MAX_CHECK_BYTES = 500_000L

    fun validKey(key: String): Boolean = key.length in 1..256 &&
        key.none(Char::isWhitespace) && ',' !in key

    /** An exact model, free access tier, and literal zero for every pricing component. */
    internal fun catalogIsFree(raw: String, expected: String = MODEL): Boolean = runCatching {
        val rows = JSONObject(raw).getJSONArray("data")
        val model = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .singleOrNull { it.optString("id") == expected } ?: return@runCatching false
        if (expected != MODEL || model.optString("access_tier") != "free") return@runCatching false
        val pricing = model.optJSONObject("pricing") ?: return@runCatching false
        if (!pricing.has("input") || !pricing.has("output")) return@runCatching false
        if (model.has("max_output_tokens") &&
            model.optInt("max_output_tokens", -1) < WEBSITE_OUTPUT_TOKENS) return@runCatching false
        val prices = pricing.keys().asSequence().filterNot { it in setOf("currency", "unit") }.toList()
        prices.isNotEmpty() && prices.all { key ->
            val amount = pricing.opt(key)?.toString() ?: return@all false
            runCatching { BigDecimal(amount).signum() == 0 }.getOrDefault(false)
        }
    }.getOrDefault(false)

    /** Unknown/free-cap-exhausted usage never enables a source-bearing request. */
    internal fun quotaIsFree(raw: String): Boolean = runCatching {
        val free = JSONObject(raw).optJSONObject("free_tokens") ?: return@runCatching false
        free.has("remaining") && !free.isNull("remaining") &&
            free.optLong("remaining", -1) >= WEBSITE_OUTPUT_TOKENS + 1_000L
    }.getOrDefault(false)

    private val checkClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS).build()

    private fun checkedGet(url: String, key: String? = null): String {
        val request = Request.Builder().url(url).apply {
            if (key != null) header("Authorization", "Bearer $key")
        }.get().build()
        return checkClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("xKiro Free preflight unavailable; no source sent")
            val bytes = response.peekBody(MAX_CHECK_BYTES + 1L).bytes()
            if (bytes.isEmpty() || bytes.size > MAX_CHECK_BYTES) {
                throw IOException("xKiro Free preflight invalid; no source sent")
            }
            String(bytes, Charsets.UTF_8)
        }
    }

    private class FreeOnlyGate : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val header = request.header("Authorization").orEmpty()
            val key = header.removePrefix("Bearer ")
            if (request.method != "POST" || request.url.toString() != ENDPOINT ||
                !header.startsWith("Bearer ") || !validKey(key)) {
                throw IOException("xKiro Free request refused; no source sent")
            }
            try {
                if (!catalogIsFree(checkedGet(MODELS)))
                    throw IOException("xKiro Free preflight: exact model/zero price unverified; no source sent")
                if (!quotaIsFree(checkedGet(USAGE, key)))
                    throw IOException("xKiro Free preflight: remaining free quota unverified; no source sent")
            } catch (failure: IOException) {
                if (failure.message?.startsWith("xKiro Free preflight") == true) throw failure
                throw IOException("xKiro Free preflight unavailable; no source sent", failure)
            } catch (failure: Exception) {
                throw IOException("xKiro Free preflight invalid; no source sent", failure)
            }
            if (chain.call().isCanceled()) throw IOException("xKiro Free request cancelled; no source sent")
            return chain.proceed(request)
        }
    }

    /** New independent adapter; no generic retry interceptor and no hidden paid routes. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(95, TimeUnit.SECONDS)
        .callTimeout(115, TimeUnit.SECONDS)
        .addInterceptor(FreeOnlyGate()).build()

    private fun transform(key: String, canonical: JSONObject, maxTokens: Int): Request {
        require(validKey(key)) { "Save a valid xKiro Free key in API & Cloud Settings" }
        val messages = canonical.getJSONArray("messages")
        val last = messages.getJSONObject(messages.length() - 1)
        require(last.optString("role") == "user" && last.optString("content").isNotEmpty()) {
            "xKiro Work requires an approved user request"
        }
        canonical.remove("provider")
        canonical.remove("plugins")
        canonical.remove("response_format")
        canonical.put("model", MODEL).put("stream", false).put("max_tokens", maxTokens)
        return Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(canonical.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    /** Canonical three-file source and goal; change only serialization, never task meaning. */
    fun websiteRequest(key: String, snapshot: WorkspaceWebsiteGeneration.Snapshot): Request {
        val canonical = WorkspaceWebsiteGeneration.request("local-body-only", snapshot)
        val buffer = Buffer()
        requireNotNull(canonical.body).writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        val system = payload.getJSONArray("messages").getJSONObject(0)
        val original = system.getString("content")
        val jsonOpening = "Return exactly ONE JSON object with only a files object containing exactly " +
            "index.html, style.css, script.js string fields. Each field is the COMPLETE new file " +
            "content, not a patch or a markdown code fence. "
        val jsonEnding = "No prose, explanations or markdown outside the JSON."
        require(original.contains(jsonOpening) && original.contains(jsonEnding)) {
            "Website contract changed; source not sent"
        }
        val blocks = "Return exactly THREE complete files as consecutive labelled code blocks, " +
            "NOT JSON or a patch. Respond with index.html then a fenced html block, style.css " +
            "then a fenced css block, script.js then a fenced javascript block, each on new lines. " +
            "Use exact lowercase labels, close every fence; use an empty script.js block if no JS. "
        system.put("content", original.replace(jsonOpening, blocks)
            .replace(jsonEnding, "No preface, duplicated file, fourth block or trailing prose."))
        return transform(key, payload, WEBSITE_OUTPUT_TOKENS)
    }

    /** One already-authorized source file, same scoped draft and local Safe Edit owner. */
    fun editRequest(key: String, preparedPrompt: String): Request {
        val message = WorkspaceConversationStore.Message("xkiro-one-file", "user", preparedPrompt,
            System.currentTimeMillis())
        return transform(key, JSONObject(WorkspaceChatGateway.openRouterBody(listOf(message))),
            EDIT_OUTPUT_TOKENS)
    }

    private fun verifiedResponse(response: Response): JSONObject {
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw IllegalStateException("xKiro Free HTTP $status; no paid fallback, no files changed")
        }
        val bytes = response.peekBody(130_001L).bytes()
        require(bytes.isNotEmpty() && bytes.size <= 130_000) { "xKiro response too large; no files changed" }
        val outer = JSONObject(String(bytes, Charsets.UTF_8))
        require(outer.optString("model") == MODEL && !outer.has("error")) {
            "xKiro did not confirm the requested free model; no files changed"
        }
        return outer
    }

    fun readWebsite(response: Response): Map<String, String> = response.use {
        verifiedResponse(it)
        WorkspaceWebsiteGeneration.readResponse(it)
    }

    fun readEdit(response: Response): String = response.use {
        val outer = verifiedResponse(it)
        val choice = outer.optJSONArray("choices")?.optJSONObject(0)
        require(choice?.optString("finish_reason") == "stop") {
            "xKiro Free reply incomplete; no files changed"
        }
        val reply = choice?.optJSONObject("message")?.opt("content") as? String
        require(!reply.isNullOrBlank() && reply.length <= 30_000) {
            "xKiro Free edit missing or oversized; no files changed"
        }
        reply
    }
}
