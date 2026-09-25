package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Direct Z.ai coding-only route. Never participates in ordinary Chat, voice or photo routing. */
internal object WorkspaceZaiFree {
    const val ENDPOINT = "https://api.z.ai/api/paas/v4/chat/completions"
    const val CODING_PREFERENCE_KEY = "workspace_zai_free_coding_source_opt_in"
    const val DEFAULT_TEXT_MODEL = "glm-4.7-flash"
    private const val MAX_RESPONSE_BYTES = 32_768L
    private const val MAX_ERROR_BODY_BYTES = 8_192L
    private const val MAX_CODING_PROMPT_CHARS = 12_000

    /** One-file Safe Edit client. One request only; no automatic retry or cross-provider fallback. */
    val client: OkHttpClient = WorkspaceFreeAiSuggestion.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Website generation normally completes slower than one-file edits, but a failed request
     * must not leave the user waiting for minutes. One request only; no retry or provider fallback.
     * The 75-second hard ceiling keeps the known ~40-45 second success case viable while failing
     * substantially earlier when the provider stalls.
     */
    val websiteClient: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    fun validKey(key: String): Boolean =
        key.isNotBlank() && key.length <= 256 && key.none(Char::isWhitespace)

    fun textModel(model: String): String = when (model) {
        DEFAULT_TEXT_MODEL -> DEFAULT_TEXT_MODEL
        else -> throw IllegalArgumentException("Unrecognized Z.ai coding model; nothing was sent")
    }

    fun displayName(model: String): String {
        textModel(model)
        return "GLM-4.7-Flash"
    }

    private fun requireKey(key: String) {
        require(validKey(key)) { "Save a valid Z.ai key in API & Cloud Settings" }
    }

    private fun requireCodingConsent(approved: Boolean) {
        require(approved) { "Z.ai Work coding source permission is OFF; no project source was sent" }
    }

    internal fun deliberationRequest(
        key: String,
        prompt: String,
        textModel: String,
        sourceIncluded: Boolean,
        sourceApproved: Boolean,
    ): Request {
        requireKey(key)
        if (sourceIncluded) requireCodingConsent(sourceApproved)
        require(prompt.isNotBlank() && prompt.length <= MAX_CODING_PROMPT_CHARS) {
            "Z.ai deliberation context exceeds safe request limit"
        }
        val payload = JSONObject()
            .put("model", WorkspaceZaiFree.textModel(textModel))
            .put("stream", false)
            .put("max_tokens", WorkspaceFreeAiSuggestion.MAX_OUTPUT_TOKENS)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            ))
        return directRequest(key, payload)
    }

    fun editRequest(key: String, prompt: String, textModel: String,
                    sourceApproved: Boolean): Request {
        requireKey(key)
        requireCodingConsent(sourceApproved)
        require(prompt.isNotBlank() && prompt.length <= MAX_CODING_PROMPT_CHARS) {
            "Selected coding source exceeds safe Z.ai request limit"
        }
        val payload = JSONObject()
            .put("model", WorkspaceZaiFree.textModel(textModel))
            .put("stream", false)
            .put("max_tokens", WorkspaceFreeAiSuggestion.MAX_OUTPUT_TOKENS)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            ))
        return directRequest(key, payload)
    }

    /** Z.ai coding is more reliable when whole source files are not JSON-escaped.
     * Only the trusted serialization instruction changes; goal/source/safety text is preserved.
     */
    private fun labelledWebsiteContract(base: String): String {
        val jsonOpening = "Return exactly ONE JSON object with only a files object containing exactly " +
            "index.html, style.css, script.js string fields. Each field is the COMPLETE new file " +
            "content, not a patch or a markdown code fence. "
        val jsonEnding = "No prose, explanations or markdown outside the JSON."
        require(base.contains(jsonOpening) && base.contains(jsonEnding)) {
            "Website output contract changed; source not sent"
        }
        val labelledOpening = "Return exactly THREE complete files as consecutive labelled code blocks, " +
            "NOT a JSON object or patch. The entire response format must be: " +
            "index.html then a fenced html block, style.css then a fenced css block, " +
            "and script.js then a fenced javascript block, each on its own lines. " +
            "Use the exact lowercase file labels; close every fence. If no JavaScript is " +
            "needed, leave the script.js block empty. Do not put triple backtick fences " +
            "inside any file block. "
        return base.replace(jsonOpening, labelledOpening)
            .replace(jsonEnding, "No preface, extra block, duplicated file or trailing explanation.")
    }
    fun websiteRequest(key: String, snapshot: WorkspaceWebsiteGeneration.Snapshot,
                       textModel: String, sourceApproved: Boolean): Request {
        requireKey(key)
        requireCodingConsent(sourceApproved)
        val canonical = WorkspaceWebsiteGeneration.request("local-body-only", snapshot)
        val buffer = Buffer()
        requireNotNull(canonical.body).writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        val messages = payload.getJSONArray("messages")
        val system = messages.getJSONObject(0)
        system.put("content", labelledWebsiteContract(system.getString("content")))
        payload.remove("provider")
        payload.remove("plugins")
        payload.remove("response_format")
        payload.put("model", WorkspaceZaiFree.textModel(textModel))
        return directRequest(key, payload)
    }

    private fun directRequest(key: String, body: JSONObject): Request =
        Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

    fun readEdit(response: Response): String = read(response)

    internal class RateLimitException(
        val retryAfterMillis: Long?,
        val businessCode: String?,
        message: String,
    ) : IllegalStateException(message)

    /** Only the bounded numeric business code is extracted; raw provider text is never exposed. */
    internal fun businessCode(response: Response): String? {
        val bytes = runCatching { response.peekBody(MAX_ERROR_BODY_BYTES + 1).bytes() }
            .getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_ERROR_BODY_BYTES) return null
        val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        val raw = root.optJSONObject("error")?.opt("code") ?: return null
        val code = when (raw) {
            is String -> raw.trim()
            is Number -> raw.toString()
            else -> return null
        }
        return code.takeIf { it.length == 4 && it.all(Char::isDigit) }
    }

    private fun retryAfterMillis(header: String?): Long? {
        val seconds = header?.takeIf { it.length in 1..3 && it.all(Char::isDigit) }
            ?.toLongOrNull() ?: return null
        return seconds.takeIf { it in 1..300 }?.times(1_000L)
    }

    private fun rateLimitCategory(code: String?): String = when (code) {
        "1113" -> "no usable balance/resource package is available for this request"
        "1302" -> "request-rate limit reached"
        "1305" -> "Z.ai service is temporarily overloaded"
        "1308" -> "usage-window limit reached; Z.ai reports that this limit resets later"
        "1309" -> "GLM Coding Plan package is expired"
        "1310" -> "weekly/monthly usage limit is exhausted; Z.ai reports that it resets later"
        "1311" -> "the current plan does not include access to the selected model"
        "1313" -> "request frequency is restricted under Z.ai Fair Usage Policy"
        "1314" -> "enterprise package is expired"
        "1315" -> "this API key is restricted to enterprise coding-package scenarios"
        "1316", "1318", "1320" -> "5-hour usage window limit reached"
        "1317", "1319", "1321" -> "7-day usage window limit reached"
        null -> "rate/usage request rejected; Z.ai did not provide a usable numeric business code"
        else -> "unrecognized Z.ai rate/usage condition"
    }

    private fun rateLimitMessage(retryAfter: String?, businessCode: String?): String {
        val wait = retryAfterMillis(retryAfter)
        val code = businessCode?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val codeText = code?.let { " Business code $it:" }.orEmpty()
        val hint = if (wait != null)
            " Wait ${wait / 1_000L} seconds before trying the coding task again."
        else " Do not retry immediately."
        return "Z.ai Free HTTP 429.$codeText ${rateLimitCategory(code)}.$hint " +
            "No automatic retry, recharge, provider switch, or paid fallback."
    }

    internal fun rateLimitFailure(retryAfter: String?, businessCode: String? = null): RateLimitException {
        val code = businessCode?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        return RateLimitException(retryAfterMillis(retryAfter), code,
            rateLimitMessage(retryAfter, code))
    }

    private fun throwHttpFailure(response: Response): Nothing = when (response.code) {
        401, 403 -> throw IllegalArgumentException(
            "Z.ai refused the API key or coding-model access (HTTP ${response.code}). No paid fallback.")
        402 -> throw IllegalArgumentException(
            "Z.ai requested payment (HTTP 402); LYRA stopped. No paid fallback.")
        429 -> throw rateLimitFailure(response.header("Retry-After"), businessCode(response))
        else -> throw IllegalArgumentException(
            "Z.ai coding request failed (HTTP ${response.code}). No paid fallback.")
    }

    fun read(response: Response): String = response.use {
        if (!it.isSuccessful) throwHttpFailure(it)
        val bytes = it.peekBody(MAX_RESPONSE_BYTES + 1).bytes()
        require(bytes.isNotEmpty() && bytes.size <= MAX_RESPONSE_BYTES) {
            "Z.ai coding response is empty or exceeds safe size"
        }
        val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("Z.ai returned invalid coding response") }
        require(!root.has("error")) { "Z.ai returned an error; no paid fallback" }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalArgumentException("Z.ai did not return a complete coding reply")
        require(choice.optString("finish_reason") == "stop") {
            "Z.ai coding reply was incomplete or filtered; no partial proposal saved"
        }
        val content = choice.optJSONObject("message")?.opt("content")
        require(content is String && content.trim().length in 1..6_000) {
            "Z.ai returned no bounded coding reply"
        }
        content.trim()
    }

    internal fun websiteHttpFailure(code: Int, businessCode: String? = null,
                                    retryAfter: String? = null): String = when (code) {
        400 -> "Z.ai Free HTTP 400: website request or output format rejected; no automatic retry or paid fallback. Project files unchanged."
        401, 403 -> "Z.ai Free HTTP $code: key or coding-model access refused; no project files changed."
        402 -> "Z.ai Free HTTP 402 requested payment; LYRA stopped. No paid fallback; project files unchanged."
        408 -> "Z.ai Free HTTP 408: upstream request timed out; no uncertain resend. Project files unchanged."
        429 -> rateLimitMessage(retryAfter, businessCode) + " Project files unchanged."
        502, 503, 504 -> "Z.ai Free HTTP $code: service unavailable; no cross-provider resend. Project files unchanged."
        else -> "Z.ai Free HTTP $code: website request refused; no paid fallback. Project files unchanged."
    }
}
