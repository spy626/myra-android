package com.myra.assistant.ui.workspace

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Public, unauthenticated, read-only GitHub adapter for Agent Reach.
 *
 * Resolve a branch/tag to an immutable commit SHA before reading README/file content.
 * No clone, archive download, script execution, write, token or login.
 */
internal object WorkspaceAgentReachGitHub {
    private const val API = "https://api.github.com"
    private const val MAX_JSON_BYTES = 256_000L
    private const val MAX_CONTENT_BYTES = 64_000
    private val safeName = Regex("""[A-Za-z0-9_.-]{1,100}""")
    private val safeSha = Regex("""[0-9a-fA-F]{40,64}""")

    data class Selection(
        val requested: WorkspaceAgentReachPolicy.Target,
        val owner: String,
        val repo: String,
        val refHint: String?,
        val path: String?,
        val isRepositoryRead: Boolean,
    )

    data class RepositoryMeta(
        val fullName: String,
        val defaultBranch: String,
        val htmlUrl: String,
        val archived: Boolean,
        val fork: Boolean,
        val licenseSpdx: String?,
    )

    enum class RootEntryKind { FILE, DIRECTORY, SYMLINK, SUBMODULE }

    data class RootEntry(
        val name: String,
        val path: String,
        val kind: RootEntryKind,
        val sha: String,
        val size: Int?,
    )

    data class RepositoryIndex(
        val commitSha: String,
        val entries: List<RootEntry>,
    ) {
        val files: Int get() = entries.count { it.kind == RootEntryKind.FILE }
        val directories: Int get() = entries.count { it.kind == RootEntryKind.DIRECTORY }
    }

    enum class PathEntryKind { FILE, DIRECTORY, SUBMODULE }

    data class PathEntry(
        val path: String,
        val kind: PathEntryKind,
        val sha: String,
        val size: Int?,
    )

    data class RepositoryPathMap(
        val commitSha: String,
        val entries: List<PathEntry>,
    ) {
        val files: Int get() = entries.count { it.kind == PathEntryKind.FILE }
        val directories: Int get() = entries.count { it.kind == PathEntryKind.DIRECTORY }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun requireName(value: String, label: String): String {
        val clean = value.trim()
        require(safeName.matches(clean) && clean != "." && clean != "..") {
            "GitHub $label is invalid"
        }
        return clean
    }

    private fun requireRef(value: String): String {
        val clean = value.trim()
        require(clean.length in 1..200 && clean.first() != '/' && clean.last() != '/' &&
            clean.all { it.isLetterOrDigit() || it in "._/-" } &&
            clean.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
            "GitHub ref is invalid"
        }
        return clean
    }

    fun selection(target: WorkspaceAgentReachPolicy.Target): Selection {
        require(target.platform == WorkspaceAgentReachPolicy.Platform.GITHUB) {
            "GitHub adapter requires a GitHub Agent Reach target"
        }
        require(target.host != "api.github.com") {
            "Paste a normal GitHub repository/file URL, not an API endpoint"
        }
        val uri = URI(target.canonicalUrl)
        val parts = uri.path.trim('/').split('/').filter(String::isNotBlank)
        val owner = requireName(target.githubOwner ?: parts.getOrNull(0).orEmpty(), "owner")
        val repo = requireName(target.githubRepo ?: parts.getOrNull(1).orEmpty(), "repository")

        return when (target.githubKind) {
            WorkspaceAgentReachPolicy.GitHubKind.REPOSITORY -> Selection(
                target, owner, repo, refHint = null, path = null, isRepositoryRead = true)
            WorkspaceAgentReachPolicy.GitHubKind.BLOB -> {
                require(parts.size >= 5 && parts[2] == "blob") {
                    "GitHub blob URL is incomplete"
                }
                val ref = requireRef(parts[3])
                val filePath = parts.drop(4).joinToString("/")
                requireSafePath(filePath)
                Selection(target, owner, repo, ref, filePath, isRepositoryRead = false)
            }
            WorkspaceAgentReachPolicy.GitHubKind.RAW_FILE -> {
                require(parts.size >= 4) { "GitHub raw-file URL is incomplete" }
                val ref = requireRef(parts[2])
                val filePath = parts.drop(3).joinToString("/")
                requireSafePath(filePath)
                Selection(target, owner, repo, ref, filePath, isRepositoryRead = false)
            }
            else -> error(
                "This Agent Reach GitHub phase supports repository, blob, and raw-file reads only")
        }
    }

    private fun requireSafePath(path: String) {
        require(path.isNotBlank() && path.length <= 1_024) { "GitHub file path is invalid" }
        require(path.split('/').all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." &&
                segment.length <= 255 && segment.none { it.isISOControl() }
        }) { "GitHub file path contains an unsafe segment" }
    }

    private fun apiUrl(path: String, query: String? = null): String =
        buildString {
            append(API)
            append(path)
            if (!query.isNullOrBlank()) {
                append('?')
                append(query)
            }
        }

    fun repositoryMetadataRequest(selection: Selection): Request {
        val owner = encode(selection.owner)
        val repo = encode(selection.repo)
        return request(apiUrl("/repos/$owner/$repo"))
    }

    fun commitRequest(selection: Selection, ref: String): Request {
        val owner = encode(selection.owner)
        val repo = encode(selection.repo)
        val cleanRef = requireRef(ref)
        return request(apiUrl("/repos/$owner/$repo/commits/${encode(cleanRef)}"))
    }

    fun rootIndexRequest(selection: Selection, commitSha: String): Request {
        require(selection.isRepositoryRead) { "Root index requires a repository read target" }
        val sha = requireSha(commitSha)
        val owner = encode(selection.owner)
        val repo = encode(selection.repo)
        return request(apiUrl("/repos/$owner/$repo/contents", "ref=${encode(sha)}"))
    }

    fun pathMapRequest(selection: Selection, commitSha: String): Request {
        require(selection.isRepositoryRead) { "Path map requires a repository read target" }
        val sha = requireSha(commitSha)
        val owner = encode(selection.owner)
        val repo = encode(selection.repo)
        return request(apiUrl(
            "/repos/$owner/$repo/git/trees/${encode(sha)}",
            "recursive=1",
        ))
    }

    fun readmeRequest(selection: Selection, commitSha: String): Request {
        require(selection.isRepositoryRead) { "README request requires a repository read target" }
        val sha = requireSha(commitSha)
        val owner = encode(selection.owner)
        val repo = encode(selection.repo)
        return request(apiUrl("/repos/$owner/$repo/readme", "ref=${encode(sha)}"))
    }

    fun pinnedRepositoryFileRequest(
        selection: Selection,
        commitSha: String,
        path: String,
    ): Request {
        require(selection.isRepositoryRead) {
            "Pinned repository-file request requires a repository read target"
        }
        val sha = requireSha(commitSha)
        requireSafePath(path)
        val owner = encode(selection.owner)
        val repo = encode(selection.repo)
        val encodedPath = path.split('/').joinToString("/") { encode(it) }
        return request(apiUrl(
            "/repos/$owner/$repo/contents/$encodedPath",
            "ref=${encode(sha)}",
        ))
    }

    fun fileRequest(selection: Selection, commitSha: String): Request {
        require(!selection.isRepositoryRead && selection.path != null) {
            "File request requires a GitHub file target"
        }
        val sha = requireSha(commitSha)
        val owner = encode(selection.owner)
        val repo = encode(selection.repo)
        val encodedPath = selection.path.split('/').joinToString("/") { encode(it) }
        return request(apiUrl("/repos/$owner/$repo/contents/$encodedPath", "ref=${encode(sha)}"))
    }

    private fun requireSha(value: String): String {
        val sha = value.trim().lowercase(Locale.US)
        require(safeSha.matches(sha)) { "GitHub commit SHA is invalid" }
        return sha
    }

    private fun request(url: String): Request {
        val target = WorkspaceAgentReachPolicy.parse(url)
        require(target.platform == WorkspaceAgentReachPolicy.Platform.GITHUB &&
            target.host == "api.github.com") {
            "GitHub API request escaped the read-only GitHub capability"
        }
        return Request.Builder()
            // URL is internally constructed from validated/encoded components above. Keep its
            // percent-encoded ref/path semantics after policy validation instead of reserializing it.
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "LYRA-AgentReach/1")
            .get()
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

    private fun readBytes(response: Response): ByteArray {
        response.use {
            require(it.code !in 300..399) { "GitHub Agent Reach redirect was refused" }
            require(it.isSuccessful) {
                when (it.code) {
                    403, 429 -> "GitHub public-read rate limit/access refused; no retry was sent"
                    404 -> "GitHub repository/ref/file was not found"
                    else -> "GitHub public read failed (HTTP ${it.code})"
                }
            }
            val body = it.peekBody(MAX_JSON_BYTES + 1).bytes()
            require(body.isNotEmpty() && body.size.toLong() <= MAX_JSON_BYTES) {
                "GitHub response was empty or too large"
            }
            return body
        }
    }

    private fun readJson(response: Response): JSONObject =
        runCatching { JSONObject(String(readBytes(response), Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("GitHub returned invalid JSON object") }

    private fun readJsonArray(response: Response): JSONArray =
        runCatching { JSONArray(String(readBytes(response), Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("GitHub returned invalid JSON array") }

    fun readRepositoryMeta(response: Response): RepositoryMeta {
        val root = readJson(response)
        val fullName = root.getString("full_name").trim()
        val defaultBranch = requireRef(root.getString("default_branch"))
        val html = WorkspaceAgentReachPolicy.parse(root.getString("html_url"))
        require(html.platform == WorkspaceAgentReachPolicy.Platform.GITHUB &&
            html.host == "github.com") { "GitHub metadata returned an invalid repository URL" }
        val license = root.optJSONObject("license")?.optString("spdx_id")
            ?.trim()?.takeIf { it.isNotBlank() && it != "NOASSERTION" }
        return RepositoryMeta(
            fullName = fullName,
            defaultBranch = defaultBranch,
            htmlUrl = html.canonicalUrl,
            archived = root.optBoolean("archived", false),
            fork = root.optBoolean("fork", false),
            licenseSpdx = license,
        )
    }

    fun readCommitSha(response: Response): String =
        requireSha(readJson(response).getString("sha"))

    fun readRootIndex(
        response: Response,
        commitSha: String,
    ): RepositoryIndex {
        val sha = requireSha(commitSha)
        val array = readJsonArray(response)
        require(array.length() <= 500) { "GitHub root index exceeds Agent Reach bound" }
        val entries = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                    ?: throw IllegalArgumentException("GitHub root index entry is invalid")
                val name = item.optString("name").trim()
                val path = item.optString("path").trim()
                require(name.isNotBlank() && name.length <= 255 &&
                    path.isNotBlank() && path.length <= 1_024 &&
                    name.none(Char::isISOControl) && path.none(Char::isISOControl)) {
                    "GitHub root index contains an unsafe path"
                }
                require(!path.split('/').any { it == "." || it == ".." }) {
                    "GitHub root index contains path traversal"
                }
                val kind = when (item.optString("type")) {
                    "file" -> RootEntryKind.FILE
                    "dir" -> RootEntryKind.DIRECTORY
                    "symlink" -> RootEntryKind.SYMLINK
                    "submodule" -> RootEntryKind.SUBMODULE
                    else -> throw IllegalArgumentException(
                        "GitHub root index contains unsupported entry type")
                }
                val entrySha = requireSha(item.optString("sha"))
                val rawSize = item.optInt("size", -1)
                add(RootEntry(
                    name = name,
                    path = path,
                    kind = kind,
                    sha = entrySha,
                    size = rawSize.takeIf { it >= 0 },
                ))
            }
        }
        return RepositoryIndex(sha, entries.sortedBy { it.path.lowercase(Locale.US) })
    }

    fun readPathMap(
        response: Response,
        commitSha: String,
    ): RepositoryPathMap {
        val sha = requireSha(commitSha)
        val root = readJson(response)
        val returnedSha = requireSha(root.getString("sha"))
        require(returnedSha == sha) { "GitHub path map revision did not match pinned commit" }
        require(!root.optBoolean("truncated", false)) {
            "GitHub recursive path map was truncated; bounded Agent Reach will not guess"
        }
        val tree = root.optJSONArray("tree")
            ?: throw IllegalArgumentException("GitHub path map is missing tree entries")
        require(tree.length() <= 1_500) {
            "GitHub path map exceeds Agent Reach entry bound"
        }
        val entries = buildList {
            for (i in 0 until tree.length()) {
                val item = tree.optJSONObject(i)
                    ?: throw IllegalArgumentException("GitHub path map entry is invalid")
                val path = item.optString("path").trim()
                require(path.isNotBlank() && path.length <= 1_024 &&
                    path.none(Char::isISOControl) &&
                    path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
                    "GitHub path map contains an unsafe path"
                }
                val kind = when (item.optString("type")) {
                    "blob" -> PathEntryKind.FILE
                    "tree" -> PathEntryKind.DIRECTORY
                    "commit" -> PathEntryKind.SUBMODULE
                    else -> throw IllegalArgumentException(
                        "GitHub path map contains unsupported entry type")
                }
                val entrySha = requireSha(item.optString("sha"))
                val rawSize = item.optInt("size", -1)
                add(PathEntry(
                    path = path,
                    kind = kind,
                    sha = entrySha,
                    size = rawSize.takeIf { it >= 0 },
                ))
            }
        }
        return RepositoryPathMap(
            commitSha = sha,
            entries = entries.sortedBy { it.path.lowercase(Locale.US) },
        )
    }

    fun readContent(
        response: Response,
        selection: Selection,
        commitSha: String,
        fetchedAtMs: Long,
    ): WorkspaceAgentReachEvidence.Evidence {
        val sha = requireSha(commitSha)
        val root = readJson(response)
        require(root.optString("type") == "file") {
            "GitHub Agent Reach expected one regular file"
        }
        require(root.optString("encoding") == "base64") {
            "GitHub file encoding is unsupported"
        }
        val declared = root.optInt("size", -1)
        require(declared in 0..MAX_CONTENT_BYTES) { "GitHub file exceeds Agent Reach read bound" }
        val encoded = root.getString("content").replace("\n", "").replace("\r", "")
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("GitHub file base64 is invalid") }
        require(bytes.size <= MAX_CONTENT_BYTES && declared == bytes.size) {
            "GitHub file size did not match the bounded response"
        }
        val content = String(bytes, Charsets.UTF_8)
        require(!content.contains('\u0000')) { "Binary GitHub file is not accepted as text evidence" }

        val htmlUrl = root.optString("html_url").takeIf { it.isNotBlank() }
            ?: selection.requested.canonicalUrl
        val finalTarget = WorkspaceAgentReachPolicy.parse(htmlUrl)
        require(finalTarget.platform == WorkspaceAgentReachPolicy.Platform.GITHUB) {
            "GitHub content provenance escaped the GitHub capability"
        }
        return WorkspaceAgentReachEvidence.create(
            requested = selection.requested,
            final = finalTarget,
            adapter = "github-public-read",
            content = content,
            fetchedAtMs = fetchedAtMs,
            revision = sha,
        )
    }
}
