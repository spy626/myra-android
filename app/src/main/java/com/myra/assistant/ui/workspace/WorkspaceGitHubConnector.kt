package com.myra.assistant.ui.workspace

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Authenticated GitHub connector transport for C1 verification only. */
internal object WorkspaceGitHubConnector {
    private const val API = "https://api.github.com"
    private const val MAX_JSON_BYTES = 128_000L

    data class Account(val login: String)
    data class Repository(val fullName: String, val privateRepo: Boolean, val defaultBranch: String)
    data class Branch(val name: String, val headSha: String)

    fun requireToken(value: String): String {
        val clean = value.trim()
        require(clean.length in 20..512 &&
            clean.none(Char::isWhitespace) &&
            clean.none(Char::isISOControl)) {
            "GitHub token is invalid"
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

    private fun readJson(response: Response, label: String): JSONObject {
        response.use {
            require(it.code !in 300..399) { "GitHub connector redirect was refused" }
            require(it.isSuccessful) {
                when (it.code) {
                    401 -> "GitHub token was rejected"
                    403 -> "GitHub access was refused for this token"
                    404 -> "GitHub repository or branch was not found for this token"
                    429 -> "GitHub rate limit reached; no automatic retry was sent"
                    else -> label + " failed (HTTP " + it.code + ")"
                }
            }
            val bytes = it.peekBody(MAX_JSON_BYTES + 1).bytes()
            require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_JSON_BYTES) {
                label + " returned an empty or oversized response"
            }
            return runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
                .getOrElse { throw IllegalArgumentException(label + " returned invalid JSON") }
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
