package com.myra.assistant.ui.workspace

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Synthetic, non-source Test Connection for a validated Custom Provider profile. */
internal object WorkspaceCustomProviderConnection {
    private const val MAX_RESPONSE_BYTES = 16_384L

    internal fun unsafePublicResolution(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.hostAddress.orEmpty().lowercase().let {
                it.startsWith("fc") || it.startsWith("fd") ||
                    it.startsWith("fe8") || it.startsWith("fe9") ||
                    it.startsWith("fea") || it.startsWith("feb")
            }

    private fun dns(profile: WorkspaceCustomProviderProfile.Validated): Dns = Dns { hostname ->
        val resolved = Dns.SYSTEM.lookup(hostname)
        require(resolved.isNotEmpty()) { "Custom API host could not be resolved" }
        if (!profile.localEndpoint) {
            require(resolved.none(::unsafePublicResolution)) {
                "Public Custom API host resolved to a local/private address; connection blocked"
            }
        }
        resolved
    }

    fun client(profile: WorkspaceCustomProviderProfile.Validated): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .connectTimeout(minOf(profile.timeoutSeconds, 20).toLong(), TimeUnit.SECONDS)
            .readTimeout(profile.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(dns(profile))
            .build()

    fun request(profile: WorkspaceCustomProviderProfile.Validated, apiKey: String): Request {
        require(apiKey.length <= 512 && apiKey.none { it == '\n' || it == '\r' }) {
            "Custom API key is too long or contains line breaks"
        }
        val body = JSONObject()
            .put("model", profile.modelId)
            .put("stream", false)
            .put("max_tokens", minOf(profile.maxOutputTokens, 16))
            .put("temperature", 0)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user")
                    .put("content", WorkspaceCustomProviderProfile.SYNTHETIC_CONNECTION_TEST_TEXT)))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(profile.chatCompletionsUrl)
            .header("Content-Type", "application/json")
            .post(body)
        if (apiKey.isNotBlank()) {
            when (profile.authMode) {
                WorkspaceCustomProviderProfile.AuthMode.BEARER ->
                    builder.header("Authorization", "Bearer $apiKey")
                WorkspaceCustomProviderProfile.AuthMode.X_API_KEY ->
                    builder.header("X-API-Key", apiKey)
            }
        }
        return builder.build()
    }

    fun read(response: Response): String {
        response.use {
            require(it.isSuccessful) {
                when (it.code) {
                    401, 403 -> "Custom API access refused. Check the endpoint, API key and auth mode."
                    402 -> "Custom API requested payment. LYRA will not enable automatic free routing."
                    404 -> "Custom API chat/completions endpoint was not found."
                    408, 504 -> "Custom API timed out. No project source was sent."
                    429 -> "Custom API is rate-limited. Try the connection test later."
                    in 300..399 -> "Custom API redirect was refused for credential safety."
                    else -> "Custom API returned HTTP ${it.code}; connection not accepted."
                }
            }
            val peek = it.peekBody(MAX_RESPONSE_BYTES + 1)
            val bytes = peek.bytes()
            require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_RESPONSE_BYTES) {
                "Custom API response was empty or too large."
            }
            val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
                .getOrElse { throw IllegalArgumentException(
                    "Custom API did not return OpenAI-compatible JSON.") }
            require(!root.has("error")) { "Custom API returned an error object." }
            val content = root.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.opt("content")
            require(content is String && content.trim().isNotEmpty()) {
                "Custom API response is missing choices[0].message.content."
            }
            return "Connection successful · OpenAI-compatible chat reply received. " +
                "Cost remains unverified and no project source was sent."
        }
    }
}
