package com.myra.assistant.ui.workspace

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import android.content.Context
import com.myra.assistant.MyApplication
import com.myra.assistant.ai.ApiKeyStore
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** Optional, user-approved xKiro Free route for Work coding ONLY. Automatic fallback requires separate opt-in.
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
    /** No inference POST was sent; cross-provider attempt is safe only with saved opt-in. */
    internal class NoSourcePreflight(message: String): IOException(message)

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
            if (!response.isSuccessful) {
                val stage = if (key == null) "model catalog" else "free quota"
                val access = if (response.code == 401 || response.code == 403)
                    "; key or account access refused" else ""
                throw NoSourcePreflight(
                    "xKiro Free $stage preflight HTTP ${response.code}$access; no source sent to xKiro")
            }
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
            val preflightFailure = try {
                if (!catalogIsFree(checkedGet(MODELS)))
                    throw NoSourcePreflight("xKiro Free preflight: exact model/zero price unverified; no source sent")
                if (!quotaIsFree(checkedGet(USAGE, key)))
                    throw NoSourcePreflight("xKiro Free preflight: remaining free quota unverified; no source sent")
                null
            } catch (failure: IOException) {
                if (failure is NoSourcePreflight) failure
                else NoSourcePreflight("xKiro Free preflight unavailable; no source sent")
            } catch (failure: Exception) {
                NoSourcePreflight("xKiro Free preflight invalid; no source sent")
            }
            if (preflightFailure != null) return consentedFallback(chain, request,
                preflightFailure.message.orEmpty(), preflightFailure)
            if (chain.call().isCanceled()) throw IOException("xKiro Free request cancelled; no source sent")
            val response = chain.proceed(request) // Network failure/timeout propagates; NEVER replay.
            if (!WorkspaceCodingAutoFallback.xKiroRejected(response.code)) return response
            val code = response.code
            response.close() // Definitive rejected HTTP, not a completed/partial output.
            return consentedFallback(chain, request, "xKiro Free HTTP $code")
        }
    }

    /** A single user-selected source can cross companies after a definite rejection or
     * before xKiro sent ANY source. No uncertain resend or nested automatic retry chain.
     */
    private fun consentedFallback(chain: Interceptor.Chain, original: Request, reason: String,
                                  preflight: NoSourcePreflight? = null): Response {
        if (chain.call().isCanceled()) throw IOException("Work request cancelled; no fallback sent")
        val context = MyApplication.contextOrNull()
        val prefs = context?.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
        val approved = prefs?.let {
            WorkspaceCodingAutoFallback.permitted(
                it.getBoolean(WorkspaceCodingAutoFallback.PREFERENCE_KEY, false),
                it.getBoolean(PREFERENCE_KEY, false))
        } == true
        if (!approved) throw preflight ?: IOException("$reason; automatic Free fallback OFF. No files changed")
        val keys = ApiKeyStore(requireNotNull(context))
        val openKey = runCatching { keys.get(ApiKeyStore.OPENROUTER) }.getOrDefault("")
        val groqKey = runCatching { keys.get(ApiKeyStore.GROQ) }.getOrDefault("")
        val groqAllowed = WorkspaceCodingAutoFallback.groqPermitted(approved,
            prefs!!.getBoolean(WorkspaceGroqFree.PREFERENCE_KEY, false), groqKey)
        val snapshot = payloadSnapshot(original)
        fun requestForGroq(): Request? = if (!groqAllowed) null else runCatching {
            if (snapshot != null) WorkspaceWebsiteGroqFallback.request(groqKey, snapshot)
            else WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.GROQ_FREE, groqKey,
                listOf(WorkspaceConversationStore.Message("approved-work-source", "user",
                    oneFilePrompt(original), System.currentTimeMillis())))
        }.getOrNull()
        val openKeyWellFormed = WorkspaceCodingAutoFallback.validKey(openKey)
        val openKeyCheck = if (openKeyWellFormed)
            WorkspaceOpenRouterKeyPreflight.verify(openKey)
        else WorkspaceOpenRouterKeyPreflight.Result.UNVERIFIED
        // A definitive key/account rejection cannot receive the project source.
        // If the read-only check is unavailable, preserve the existing consented free route.
        val openAllowed = openKeyWellFormed &&
            openKeyCheck != WorkspaceOpenRouterKeyPreflight.Result.ACCESS_REFUSED
        val open = if (openAllowed) runCatching {
            if (snapshot != null) WorkspaceWebsiteGeneration.request(openKey, snapshot)
            else WorkspaceChatGateway.request(WorkspaceChatGateway.Provider.OPENROUTER_FREE, openKey,
                listOf(WorkspaceConversationStore.Message("approved-work-source", "user",
                    oneFilePrompt(original), System.currentTimeMillis())))
        }.getOrNull() else null
        var openRouterResult = when {
            !openKeyWellFormed -> "OpenRouter Free key missing or invalid"
            !openAllowed -> "OpenRouter Free key/account access refused by read-only key check; no source sent to OpenRouter"
            else -> "OpenRouter Free request could not be prepared"
        }
        if (open != null) {
            if (chain.call().isCanceled()) throw IOException("Work request cancelled; no fallback sent")
            val second = chain.proceed(open) // Any network ambiguity ends the chain.
            if (!WorkspaceCodingAutoFallback.openRouterRejected(second.code)) return second
            openRouterResult = "OpenRouter Free HTTP ${second.code}"
            second.close()
        }
        val groq = requestForGroq() ?: throw IOException(
            "$reason; $openRouterResult; no eligible Groq Free/ZDR route remains; files unchanged")
        if (chain.call().isCanceled()) throw IOException("Work request cancelled; no fallback sent")
        return chain.proceed(groq) // One Groq attempt; no paid or recursive fallback.
    }

    private fun payloadSnapshot(request: Request): WorkspaceWebsiteGeneration.Snapshot? {
        val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        if (body.optInt("max_tokens") != WEBSITE_OUTPUT_TOKENS) return null
        val text = body.getJSONArray("messages").getJSONObject(1).getString("content")
        val context = JSONObject(text)
        val sources = context.getJSONObject("existingFiles")
        val original = WorkspaceWebsiteGeneration.PATHS.associateWith { path ->
            if (sources.isNull(path)) null else sources.getString(path)
        }
        return WorkspaceWebsiteGeneration.Snapshot("", "", "", context.getString("goal"), original)
    }

    private fun oneFilePrompt(request: Request): String {
        val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
        val messages = JSONObject(buffer.readUtf8()).getJSONArray("messages")
        return messages.getJSONObject(messages.length() - 1).getString("content")
    }

    /** New independent adapter; no generic retry interceptor and no hidden paid routes. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(95, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS) // Total bound includes up to two definite free fallbacks.
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
        if (it.request.url.toString() == ENDPOINT) verifiedResponse(it)
        WorkspaceWebsiteGeneration.readResponse(it)
    }

    fun readEdit(response: Response): String = response.use {
        if (it.request.url.toString() == WorkspaceFreeAiSuggestion.ENDPOINT)
            return@use WorkspaceFreeAiSuggestion.readResponse(it)
        if (it.request.url.toString() == WorkspaceGroqFree.ENDPOINT)
            return@use WorkspaceGroqFree.read(it)
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
