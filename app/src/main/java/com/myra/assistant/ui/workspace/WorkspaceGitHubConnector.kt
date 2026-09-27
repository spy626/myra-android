package com.myra.assistant.ui.workspace

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Authenticated GitHub connector transport for verified read access and OAuth token exchange. */
internal object WorkspaceGitHubConnector {
    private const val API = "https://api.github.com"
    const val OAUTH_BROKER = "https://lyra-github-connector.everspy626.workers.dev"
    private const val MAX_JSON_BYTES = 128_000L
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val urlSafe = Regex("[A-Za-z0-9._~-]{20,256}")

    data class Account(val login: String)
    data class Repository(val fullName: String, val privateRepo: Boolean, val defaultBranch: String)
    data class Branch(val name: String, val headSha: String)
    data class OAuthTokens(
        val accessToken: String,
        val expiresInSeconds: Long?,
        val refreshToken: String?,
        val refreshTokenExpiresInSeconds: Long?,
    )

    fun requireToken(value: String): String {
        val clean = value.trim()
        require(clean.length in 20..512 &&
            clean.none(Char::isWhitespace) &&
            clean.none(Char::isISOControl)) {
            "GitHub token is invalid"
        }
        return clean
    }

    private fun requireUrlSafe(value: String, label: String, min: Int = 20, max: Int = 256): String {
        val clean = value.trim()
        require(clean.length in min..max && urlSafe.matches(clean)) { "$label is invalid" }
        return clean
    }

    private fun requireCode(value: String): String {
        val clean = value.trim()
        require(clean.length in 10..512 &&
            clean.none(Char::isWhitespace) &&
            clean.none(Char::isISOControl)) {
            "GitHub authorization code is invalid"
        }
        return clean
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun request(path: String, token: String): Request {
        val cleanToken = requireToken(token)
        require(path.startsWith("/") && !path.contains("://")) {
            "GitHub connector path is invalid"
        }
        return Request.Builder()
            .url(API + path)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "LYRA-Connector/1")
            .header("Authorization", "Bearer " + cleanToken)
            .get()
            .build()
    }

    fun userRequest(token: String): Request = request("/user", token)

    fun repositoryRequest(token: String, repository: String): Request {
        val clean = WorkspaceConnectorPolicy.requireRepository(repository)
        val parts = clean.split('/')
        return request("/repos/" + encode(parts[0]) + "/" + encode(parts[1]), token)
    }

    fun branchRequest(token: String, repository: String, branch: String): Request {
        val clean = WorkspaceConnectorPolicy.binding(repository, branch)
        val parts = clean.repository.split('/')
        return request(
            "/repos/" + encode(parts[0]) + "/" + encode(parts[1]) +
                "/branches/" + encode(clean.branch),
            token,
        )
    }

    fun oauthExchangeRequest(
        code: String,
        state: String,
        verifier: String,
    ): Request {
        val payload = JSONObject()
            .put("code", requireCode(code))
            .put("state", requireUrlSafe(state, "OAuth state", 20, 160))
            .put("code_verifier", requireUrlSafe(verifier, "PKCE verifier", 43, 128))
        return Request.Builder()
            .url(OAUTH_BROKER + "/github/exchange")
            .header("Accept", "application/json")
            .header("User-Agent", "LYRA-Connector/1")
            .post(payload.toString().toRequestBody(JSON))
            .build()
    }

    private val guardedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            WorkspaceAgentReachPolicy.validateResolvedAddresses(hostname, addresses)
            return addresses
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .dns(guardedDns)
        .build()

    private fun parseJson(response: Response, label: String): Pair<Response, JSONObject> {
        require(response.code !in 300..399) { "$label redirect was refused" }
        val bytes = response.peekBody(MAX_JSON_BYTES + 1).bytes()
        require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_JSON_BYTES) {
            "$label returned an empty or oversized response"
        }
        val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("$label returned invalid JSON") }
        return response to root
    }

    private fun readJson(response: Response, label: String): JSONObject {
        response.use {
            val (_, root) = parseJson(it, label)
            require(it.isSuccessful) {
                when (it.code) {
                    401 -> "GitHub token was rejected"
                    403 -> "GitHub access was refused for this token"
                    404 -> "GitHub repository or branch was not found for this token"
                    429 -> "GitHub rate limit reached; no automatic retry was sent"
                    else -> label + " failed (HTTP " + it.code + ")"
                }
            }
            return root
        }
    }

    fun readOAuthTokens(response: Response): OAuthTokens {
        response.use {
            val (_, root) = parseJson(it, "GitHub OAuth exchange")
            require(it.isSuccessful) {
                root.optString("description").trim().take(180)
                    .ifBlank { "GitHub OAuth exchange failed (HTTP " + it.code + ")" }
            }
            val tokenType = root.optString("token_type", "bearer").trim()
            require(tokenType.equals("bearer", ignoreCase = true)) {
                "GitHub OAuth token type is invalid"
            }
            val accessToken = requireToken(root.optString("access_token"))
            val refreshToken = root.optString("refresh_token").trim()
                .takeIf(String::isNotBlank)
                ?.let(::requireToken)

            fun duration(name: String): Long? {
                if (!root.has(name) || root.isNull(name)) return null
                val value = root.optLong(name, -1L)
                require(value in 1L..31_536_000L) { "GitHub OAuth expiry is invalid" }
                return value
            }

            return OAuthTokens(
                accessToken = accessToken,
                expiresInSeconds = duration("expires_in"),
                refreshToken = refreshToken,
                refreshTokenExpiresInSeconds = duration("refresh_token_expires_in"),
            )
        }
    }

    fun readAccount(response: Response): Account {
        val root = readJson(response, "GitHub account verification")
        val login = root.optString("login").trim()
        require(login.length in 1..100 && login.none(Char::isISOControl)) {
            "GitHub account response is invalid"
        }
        return Account(login)
    }

    fun readRepository(response: Response, expectedRepository: String): Repository {
        val expected = WorkspaceConnectorPolicy.requireRepository(expectedRepository)
        val root = readJson(response, "GitHub repository verification")
        val fullName = root.optString("full_name").trim()
        require(fullName.equals(expected, ignoreCase = true)) {
            "GitHub repository identity did not match"
        }
        val defaultBranch = root.optString("default_branch").trim()
        require(defaultBranch.isNotBlank() && defaultBranch.length <= 200) {
            "GitHub repository default branch is invalid"
        }
        return Repository(
            fullName = fullName,
            privateRepo = root.optBoolean("private", false),
            defaultBranch = defaultBranch,
        )
    }

    fun readBranch(response: Response, expectedBranch: String): Branch {
        val expected = WorkspaceConnectorPolicy.requireFeatureBranch(expectedBranch)
        val root = readJson(response, "GitHub branch verification")
        val name = root.optString("name").trim()
        require(name == expected) { "GitHub branch identity did not match" }
        val sha = root.optJSONObject("commit")?.optString("sha").orEmpty().trim()
        require(Regex("[0-9a-fA-F]{40,64}").matches(sha)) {
            "GitHub branch head SHA is invalid"
        }
        return Branch(name, sha.lowercase())
    }
}
