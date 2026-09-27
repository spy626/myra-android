package com.myra.assistant.ui.workspace

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Ephemeral Android-side state for the automatic GitHub App browser authorization flow. */
internal object WorkspaceGitHubOAuthSession {
    private const val MAX_AGE_MS = 15 * 60 * 1000L
    private val random = SecureRandom()
    private val urlSafe = Regex("[A-Za-z0-9._~-]{20,256}")

    data class Pending(
        val state: String,
        val verifier: String,
        val challenge: String,
        val createdAtMs: Long,
    )

    sealed interface Callback {
        data class Success(val code: String) : Callback
        data class Denied(val reason: String) : Callback
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun requireUrlSafe(value: String, label: String): String {
        val clean = value.trim()
        require(urlSafe.matches(clean)) { "$label is invalid" }
        return clean
    }

    fun create(nowMs: Long = System.currentTimeMillis()): Pending {
        require(nowMs >= 0L) { "OAuth timestamp is invalid" }
        val stateBytes = ByteArray(32).also(random::nextBytes)
        val verifierBytes = ByteArray(64).also(random::nextBytes)
        return createForTest(stateBytes, verifierBytes, nowMs)
    }

    internal fun createForTest(
        stateBytes: ByteArray,
        verifierBytes: ByteArray,
        nowMs: Long,
    ): Pending {
        require(stateBytes.size >= 24) { "OAuth state entropy is too small" }
        require(verifierBytes.size >= 32) { "PKCE verifier entropy is too small" }
        val state = base64Url(stateBytes)
        val verifier = base64Url(verifierBytes)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        )
        require(verifier.length in 43..128) { "PKCE verifier length is invalid" }
        require(challenge.length == 43) { "PKCE challenge length is invalid" }
        return Pending(state, verifier, challenge, nowMs)
    }

    fun restore(
        state: String,
        verifier: String,
        challenge: String,
        createdAtMs: Long,
    ): Pending {
        require(createdAtMs >= 0L) { "OAuth timestamp is invalid" }
        val cleanState = requireUrlSafe(state, "OAuth state")
        val cleanVerifier = requireUrlSafe(verifier, "PKCE verifier")
        val cleanChallenge = requireUrlSafe(challenge, "PKCE challenge")
        require(cleanVerifier.length in 43..128) { "PKCE verifier length is invalid" }
        require(cleanChallenge.length == 43) { "PKCE challenge length is invalid" }
        val expectedChallenge = base64Url(
            MessageDigest.getInstance("SHA-256")
                .digest(cleanVerifier.toByteArray(StandardCharsets.US_ASCII))
        )
        require(expectedChallenge == cleanChallenge) { "PKCE challenge does not match verifier" }
        return Pending(cleanState, cleanVerifier, cleanChallenge, createdAtMs)
    }

    fun connectUrl(authBaseUrl: String, pending: Pending): String {
        val base = URI(authBaseUrl.trim())
        require(base.scheme == "https" && !base.host.isNullOrBlank() &&
            base.userInfo == null && base.fragment == null) {
            "GitHub connector service must use HTTPS"
        }
        requireUrlSafe(pending.state, "OAuth state")
        requireUrlSafe(pending.challenge, "PKCE challenge")
        val path = base.path.orEmpty().trimEnd('/') + "/github/authorize"
        val query = "state=" + encode(pending.state) +
            "&code_challenge=" + encode(pending.challenge)
        return URI("https", null, base.host, base.port, path, query, null).toASCIIString()
    }

    fun parseCallback(
        rawUri: String,
        pending: Pending,
        nowMs: Long = System.currentTimeMillis(),
    ): Callback {
        require(nowMs >= pending.createdAtMs &&
            nowMs - pending.createdAtMs <= MAX_AGE_MS) {
            "GitHub authorization expired; connect again"
        }
        val uri = URI(rawUri)
        require(uri.scheme == "lyra" && uri.host == "github" && uri.path == "/callback") {
            "GitHub callback target is invalid"
        }
        val params = uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .associate { part ->
                val index = part.indexOf('=')
                val key = if (index >= 0) part.substring(0, index) else part
                val value = if (index >= 0) part.substring(index + 1) else ""
                decode(key) to decode(value)
            }
        val returnedState = requireUrlSafe(params["state"].orEmpty(), "OAuth state")
        require(returnedState == pending.state) {
            "GitHub authorization state mismatch"
        }
        params["error"]?.takeIf(String::isNotBlank)?.let {
            return Callback.Denied(it.take(120))
        }
        val code = params["code"].orEmpty().trim()
        require(code.length in 10..512 && code.none(Char::isWhitespace)) {
            "GitHub authorization code is invalid"
        }
        return Callback.Success(code)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun decode(value: String): String =
        java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
