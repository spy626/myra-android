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
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** GitHub App installation-token transport with one-time local/Cloudflare pairing. */
internal object WorkspaceGitHubConnector {
    private const val API = "https://api.github.com"
    const val BROKER = "https://lyra-github-connector.everspy626.workers.dev"
    private const val MAX_JSON_BYTES = 128_000L
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val pairingPattern = Regex("[0-9a-f]{64}")

    data class Repository(val fullName: String, val privateRepo: Boolean, val defaultBranch: String)
    data class Branch(val name: String, val headSha: String)
    data class InstallationGrant(
        val accessToken: String,
        val expiresInSeconds: Long,
        val login: String,
        val repository: String,
        val branch: String,
    )

    fun requireToken(value: String): String {
        val clean = value.trim()
        require(clean.length in 20..1024 &&
            clean.none(Char::isWhitespace) &&
            clean.none(Char::isISOControl)) {
            "GitHub token is invalid"
        }
        return clean
    }

    fun requirePairingSecret(value: String): String {
        val clean = value.trim().lowercase()
        require(pairingPattern.matches(clean)) { "LYRA pairing key is invalid" }
        return clean
    }

    fun newPairingSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return buildString(64) {
            bytes.forEach { byte ->
                append(((byte.toInt() ushr 4) and 0x0f).toString(16))
                append((byte.toInt() and 0x0f).toString(16))
            }
        }
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
            .header("User-Agent", "LYRA-Connector/2")
            .header("Authorization", "Bearer " + cleanToken)
            .get()
            .build()
    }

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

    fun installationTokenRequest(pairingSecret: String): Request {
        val payload = JSONObject()
            .put("pairing_secret", requirePairingSecret(pairingSecret))
        return Request.Builder()
            .url(BROKER + "/github/token")
            .header("Accept", "application/json")
            .header("User-Agent", "LYRA-Connector/2")
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
                val description = root.optString("description").trim().take(180)
                when (it.code) {
                    400 -> description.ifBlank { "$label request was rejected" }
                    401 -> description.ifBlank { "LYRA pairing key was rejected" }
                    403 -> "GitHub access was refused for this installation"
                    404 -> "GitHub repository or branch was not found for this installation"
                    429 -> "GitHub rate limit reached; no automatic retry was sent"
                    else -> description.ifBlank { "$label failed (HTTP " + it.code + ")" }
                }
            }
            return root
        }
    }

    fun readInstallationGrant(response: Response): InstallationGrant {
        val root = readJson(response, "GitHub installation connection")
        val tokenType = root.optString("token_type", "bearer").trim()
        require(tokenType.equals("bearer", ignoreCase = true)) {
            "GitHub installation token type is invalid"
        }
        val expiresIn = root.optLong("expires_in", -1L)
        require(expiresIn in 60L..3_700L) {
            "GitHub installation token expiry is invalid"
        }
        val login = root.optString("login").trim()
        require(login.length in 1..100 && login.none(Char::isISOControl)) {
            "GitHub installation account is invalid"
        }
        val repository = WorkspaceConnectorPolicy.requireRepository(
            root.optString("repository"),
        )
        val branch = WorkspaceConnectorPolicy.requireFeatureBranch(
            root.optString("branch"),
        )
        return InstallationGrant(
            accessToken = requireToken(root.optString("access_token")),
            expiresInSeconds = expiresIn,
            login = login,
            repository = repository,
            branch = branch,
        )
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
