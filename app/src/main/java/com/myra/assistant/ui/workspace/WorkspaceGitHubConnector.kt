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
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** GitHub App installation transport with read tokens plus broker-gated C2 writes. */
internal object WorkspaceGitHubConnector {
    private const val API = "https://api.github.com"
    const val BROKER = "https://lyra-github-connector.everspy626.workers.dev"
    private const val MAX_JSON_BYTES = 256_000L
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val pairingPattern = Regex("[0-9a-f]{64}")
    private val shaPattern = Regex("[0-9a-fA-F]{40,64}")

    data class Repository(val fullName: String, val privateRepo: Boolean, val defaultBranch: String)
    data class Branch(val name: String, val headSha: String)
    data class WorkflowRun(
        val id: Long,
        val runNumber: Long,
        val name: String,
        val headSha: String,
        val status: String,
        val conclusion: String?,
        val url: String,
    )
    data class WorkflowFailure(
        val runId: Long,
        val runNumber: Long,
        val workflowName: String,
        val jobName: String,
        val failedSteps: List<String>,
    ) {
        fun boundedSummary(): String {
            val steps = if (failedSteps.isEmpty()) "unknown failing step"
                else failedSteps.joinToString(", ")
            return "GitHub Actions " + workflowName + " #" + runNumber +
                " failed; job=" + jobName + "; failed_steps=" + steps +
                "; run_id=" + runId
        }
    }
    data class InstallationGrant(
        val accessToken: String,
        val expiresInSeconds: Long,
        val login: String,
        val repository: String,
        val branch: String,
    )
    data class WriteAccess(
        val repository: String,
        val branch: String,
        val headSha: String,
        val prBase: String,
    )
    data class CommitReceipt(
        val repository: String,
        val branch: String,
        val previousHead: String,
        val commitSha: String,
        val files: List<String>,
    )
    data class PullRequestReceipt(
        val action: String,
        val number: Int,
        val url: String,
        val draft: Boolean,
        val head: String,
        val base: String,
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

    private fun requireSha(value: String, label: String): String {
        val clean = value.trim().lowercase()
        require(shaPattern.matches(clean)) { "$label SHA is invalid" }
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
            .header("User-Agent", "LYRA-Connector/3")
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

    fun pathMapRequest(token: String, repository: String, headSha: String): Request {
        val clean = WorkspaceConnectorPolicy.requireRepository(repository)
        val sha = requireSha(headSha, "GitHub feature-branch head")
        val parts = clean.split('/')
        return request(
            "/repos/" + encode(parts[0]) + "/" + encode(parts[1]) +
                "/git/trees/" + encode(sha) + "?recursive=1",
            token,
        )
    }

    fun fileContentRequest(
        token: String,
        repository: String,
        headSha: String,
        path: String,
    ): Request {
        val clean = WorkspaceConnectorPolicy.requireRepository(repository)
        val sha = requireSha(headSha, "GitHub feature-branch head")
        val safePath = WorkspaceGitHubWritePolicy.requirePath(path)
        val parts = clean.split('/')
        val encodedPath = safePath.split('/').joinToString("/") { encode(it) }
        return request(
            "/repos/" + encode(parts[0]) + "/" + encode(parts[1]) +
                "/contents/" + encodedPath + "?ref=" + encode(sha),
            token,
        )
    }

    fun workflowRunsRequest(
        token: String,
        repository: String,
        branch: String,
        headSha: String,
    ): Request {
        val clean = WorkspaceConnectorPolicy.binding(repository, branch)
        val sha = requireSha(headSha, "GitHub workflow commit")
        val parts = clean.repository.split('/')
        return request(
            "/repos/" + encode(parts[0]) + "/" + encode(parts[1]) +
                "/actions/runs?branch=" + encode(clean.branch) +
                "&head_sha=" + encode(sha) + "&event=push&per_page=5",
            token,
        )
    }

    fun workflowRunJobsRequest(
        token: String,
        repository: String,
        runId: Long,
    ): Request {
        val clean = WorkspaceConnectorPolicy.requireRepository(repository)
        require(runId > 0L) { "GitHub workflow run id is invalid" }
        val parts = clean.split('/')
        return request(
            "/repos/" + encode(parts[0]) + "/" + encode(parts[1]) +
                "/actions/runs/" + runId + "/jobs?filter=latest&per_page=100",
            token,
        )
    }

    private fun brokerPost(path: String, payload: JSONObject): Request {
        require(path.startsWith("/github/") && !path.contains("://")) {
            "GitHub broker path is invalid"
        }
        return Request.Builder()
            .url(BROKER + path)
            .header("Accept", "application/json")
            .header("User-Agent", "LYRA-Connector/3")
            .post(payload.toString().toRequestBody(JSON))
            .build()
    }

    fun installationTokenRequest(pairingSecret: String): Request =
        brokerPost(
            "/github/token",
            JSONObject().put("pairing_secret", requirePairingSecret(pairingSecret)),
        )

    fun writeAccessRequest(pairingSecret: String): Request =
        brokerPost(
            "/github/write/check",
            JSONObject().put("pairing_secret", requirePairingSecret(pairingSecret)),
        )

    fun commitRequest(
        pairingSecret: String,
        plan: WorkspaceGitHubWritePolicy.CommitPlan,
    ): Request {
        val checked = WorkspaceGitHubWritePolicy.commitPlan(
            plan.expectedHead,
            plan.message,
            plan.files,
        )
        val files = JSONArray()
        checked.files.forEach { change ->
            files.put(JSONObject().put("path", change.path).put("content", change.content))
        }
        return brokerPost(
            "/github/write/commit",
            JSONObject()
                .put("pairing_secret", requirePairingSecret(pairingSecret))
                .put("expected_head", checked.expectedHead)
                .put("message", checked.message)
                .put("files", files),
        )
    }

    fun pullRequestRequest(
        pairingSecret: String,
        plan: WorkspaceGitHubWritePolicy.PullRequestPlan,
    ): Request {
        val checked = WorkspaceGitHubWritePolicy.pullRequestPlan(plan.title, plan.body)
        return brokerPost(
            "/github/write/pull-request",
            JSONObject()
                .put("pairing_secret", requirePairingSecret(pairingSecret))
                .put("title", checked.title)
                .put("body", checked.body),
        )
    }

    fun pullRequestSmokeTestRequest(pairingSecret: String): Request =
        brokerPost(
            "/github/write/pull-request",
            JSONObject()
                .put("pairing_secret", requirePairingSecret(pairingSecret))
                .put("smoke_test", true),
        )

    fun ensureDraftPullRequestRequest(pairingSecret: String): Request =
        brokerPost(
            "/github/write/pull-request",
            JSONObject()
                .put("pairing_secret", requirePairingSecret(pairingSecret))
                .put("preserve_existing", true),
        )

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

    private fun parseJson(response: Response, label: String): JSONObject {
        response.use {
            require(it.code !in 300..399) { "$label redirect was refused" }
            val bytes = it.peekBody(MAX_JSON_BYTES + 1).bytes()
            require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_JSON_BYTES) {
                "$label returned an empty or oversized response"
            }
            val root = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
                .getOrElse { throw IllegalArgumentException("$label returned invalid JSON") }
            require(it.isSuccessful) {
                val description = root.optString("description").trim().take(220)
                when (it.code) {
                    400 -> description.ifBlank { "$label request was rejected" }
                    401 -> description.ifBlank { "LYRA pairing key was rejected" }
                    403 -> "GitHub access was refused for this installation"
                    404 -> "GitHub repository or branch was not found for this installation"
                    409 -> description.ifBlank { "GitHub feature branch changed; refresh and retry" }
                    422 -> description.ifBlank {
                        "GitHub App write permissions are not granted to this installation"
                    }
                    429 -> "GitHub rate limit reached; no automatic retry was sent"
                    else -> description.ifBlank { "$label failed (HTTP " + it.code + ")" }
                }
            }
            return root
        }
    }

    fun readInstallationGrant(response: Response): InstallationGrant {
        val root = parseJson(response, "GitHub installation connection")
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
        val repository = WorkspaceConnectorPolicy.requireRepository(root.optString("repository"))
        val branch = WorkspaceConnectorPolicy.requireFeatureBranch(root.optString("branch"))
        return InstallationGrant(
            accessToken = requireToken(root.optString("access_token")),
            expiresInSeconds = expiresIn,
            login = login,
            repository = repository,
            branch = branch,
        )
    }

    fun readWriteAccess(response: Response): WriteAccess {
        val root = parseJson(response, "GitHub write permission verification")
        require(root.optBoolean("enabled", false)) { "GitHub write access is not enabled" }
        val repository = WorkspaceConnectorPolicy.requireRepository(root.optString("repository"))
        val branch = WorkspaceConnectorPolicy.requireFeatureBranch(root.optString("branch"))
        val prBase = root.optString("pr_base").trim()
        require(prBase in setOf("main", "master")) { "GitHub PR base is invalid" }
        return WriteAccess(
            repository = repository,
            branch = branch,
            headSha = requireSha(root.optString("head"), "GitHub feature-branch head"),
            prBase = prBase,
        )
    }

    fun readCommitReceipt(response: Response): CommitReceipt {
        val root = parseJson(response, "GitHub feature-branch commit")
        require(root.optBoolean("committed", false)) { "GitHub commit was not confirmed" }
        val array = root.optJSONArray("files") ?: JSONArray()
        val files = buildList {
            for (i in 0 until array.length()) {
                add(WorkspaceGitHubWritePolicy.requirePath(array.getString(i)))
            }
        }
        return CommitReceipt(
            repository = WorkspaceConnectorPolicy.requireRepository(root.optString("repository")),
            branch = WorkspaceConnectorPolicy.requireFeatureBranch(root.optString("branch")),
            previousHead = requireSha(root.optString("previous_head"), "GitHub previous head"),
            commitSha = requireSha(root.optString("commit_sha"), "GitHub commit"),
            files = files,
        )
    }

    fun readPullRequestReceipt(response: Response): PullRequestReceipt {
        val root = parseJson(response, "GitHub draft pull request")
        val action = root.optString("action").trim()
        require(action in setOf("created", "updated")) { "GitHub PR action is invalid" }
        val number = root.optInt("number", -1)
        require(number > 0) { "GitHub PR number is invalid" }
        val url = root.optString("url").trim()
        require(url.startsWith("https://github.com/")) { "GitHub PR URL is invalid" }
        return PullRequestReceipt(
            action = action,
            number = number,
            url = url,
            draft = root.optBoolean("draft", false),
            head = WorkspaceConnectorPolicy.requireFeatureBranch(root.optString("head")),
            base = root.optString("base").trim().also {
                require(it in setOf("main", "master")) { "GitHub PR base is invalid" }
            },
        )
    }

    fun readWorkflowRunForHead(
        response: Response,
        expectedHeadSha: String,
    ): WorkflowRun? {
        val expected = requireSha(expectedHeadSha, "GitHub workflow commit")
        val root = parseJson(response, "GitHub Actions workflow runs")
        val array = root.optJSONArray("workflow_runs") ?: JSONArray()
        val matches = mutableListOf<WorkflowRun>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val head = item.optString("head_sha").trim().lowercase()
            if (head != expected) continue
            val name = item.optString("name").trim()
            if (name != "Build Android APK") continue
            val id = item.optLong("id", -1L)
            val runNumber = item.optLong("run_number", -1L)
            val status = item.optString("status").trim()
            val url = item.optString("html_url").trim()
            require(id > 0L && runNumber > 0L && status.isNotBlank()) {
                "GitHub Actions workflow run metadata is invalid"
            }
            require(url.startsWith("https://github.com/")) {
                "GitHub Actions workflow URL is invalid"
            }
            val conclusion = item.optString("conclusion").trim()
                .takeIf { it.isNotBlank() && it != "null" }
            matches += WorkflowRun(
                id = id,
                runNumber = runNumber,
                name = name,
                headSha = head,
                status = status,
                conclusion = conclusion,
                url = url,
            )
        }
        return matches.maxByOrNull { it.id }
    }

    fun readWorkflowFailure(
        response: Response,
        run: WorkflowRun,
    ): WorkflowFailure {
        val root = parseJson(response, "GitHub Actions workflow jobs")
        val array = root.optJSONArray("jobs") ?: JSONArray()
        var failedJob = "unknown job"
        val steps = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val job = array.optJSONObject(i) ?: continue
            val conclusion = job.optString("conclusion").trim()
            if (conclusion !in setOf("failure", "timed_out", "cancelled", "action_required", "stale")) {
                continue
            }
            val name = job.optString("name").trim().takeIf { it.isNotBlank() } ?: "unknown job"
            if (failedJob == "unknown job") failedJob = name
            val jobSteps = job.optJSONArray("steps") ?: JSONArray()
            for (j in 0 until jobSteps.length()) {
                val step = jobSteps.optJSONObject(j) ?: continue
                val stepConclusion = step.optString("conclusion").trim()
                if (stepConclusion !in setOf("failure", "timed_out", "cancelled")) continue
                step.optString("name").trim().takeIf { it.isNotBlank() }?.let(steps::add)
            }
        }
        return WorkflowFailure(
            runId = run.id,
            runNumber = run.runNumber,
            workflowName = run.name,
            jobName = failedJob,
            failedSteps = steps.distinct().take(8),
        )
    }

    fun readPathMap(
        response: Response,
        expectedHeadSha: String,
    ): WorkspaceAgentReachGitHub.RepositoryPathMap =
        WorkspaceAgentReachGitHub.readPathMap(
            response,
            requireSha(expectedHeadSha, "GitHub feature-branch head"),
        )

    fun readTextFile(response: Response, expectedPath: String): String {
        val safePath = WorkspaceGitHubWritePolicy.requirePath(expectedPath)
        val root = parseJson(response, "GitHub source file read")
        require(root.optString("type") == "file" && root.optString("path") == safePath) {
            "GitHub source file identity did not match"
        }
        require(root.optString("encoding") == "base64") {
            "GitHub source file encoding is unsupported"
        }
        val declared = root.optInt("size", -1)
        require(declared in 0..WorkspaceGitHubWritePolicy.MAX_FILE_BYTES) {
            "GitHub source file exceeds LYRA's bounded write size"
        }
        val encoded = root.optString("content").replace("\n", "")
        require(encoded.isNotBlank() && encoded.length <=
            ((WorkspaceGitHubWritePolicy.MAX_FILE_BYTES * 4 / 3) + 16_384)) {
            "GitHub source file content is missing or oversized"
        }
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("GitHub source file base64 is invalid") }
        require(bytes.size <= WorkspaceGitHubWritePolicy.MAX_FILE_BYTES) {
            "GitHub source file exceeds LYRA's bounded write size"
        }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun readRepository(response: Response, expectedRepository: String): Repository {
        val expected = WorkspaceConnectorPolicy.requireRepository(expectedRepository)
        val root = parseJson(response, "GitHub repository verification")
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
        val root = parseJson(response, "GitHub branch verification")
        val name = root.optString("name").trim()
        require(name == expected) { "GitHub branch identity did not match" }
        val sha = root.optJSONObject("commit")?.optString("sha").orEmpty().trim()
        return Branch(name, requireSha(sha, "GitHub branch head"))
    }
}
