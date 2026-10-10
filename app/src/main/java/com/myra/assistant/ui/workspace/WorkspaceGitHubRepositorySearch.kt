package com.myra.assistant.ui.workspace

import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.log10

/**
 * Read-only discovery for public GitHub repositories, distinct from generic HTML search.
 * Uses GitHub's public search API, without user credentials, cookies or any write authority.
 * All discovered destinations still pass WorkspaceAgentReachPolicy and live verification.
 */
internal object WorkspaceGitHubRepositorySearch {
    private const val ORIGIN = "https://api.github.com/search/repositories"
    private const val MAX_SEARCH_BYTES = 512_000L
    private val repositoryCue = Regex("""(?iu)\b(?:repo|repos|repository|repositories)\b""")
    private val namePart = Regex("""[\p{L}\p{N}_.-]{2,64}""")
    private val filler = setOf(
        "repo", "repos", "repository", "repositories", "github", "official",
        "project", "projects", "source", "code", "link", "url", "the", "for",
    )

    fun supports(request: WorkspaceWebLinkIntent.Request): Boolean =
        request.preferredHost == "github.com" &&
            repositoryCue.containsMatchIn(request.query) &&
            projectTerms(request.query).isNotEmpty()

    private fun projectTerms(query: String): List<String> =
        namePart.findAll(query).map { it.value.lowercase(Locale.ROOT) }
            .filterNot { it in filler }.take(4).toList()

    fun searchRequest(request: WorkspaceWebLinkIntent.Request): Request {
        require(supports(request)) { "Public GitHub repository query is not supported" }
        val words = projectTerms(request.query).joinToString(" ")
        val parameter = URLEncoder.encode(
            words + " in:name fork:false",
            StandardCharsets.UTF_8.name(),
        )
        val url = ORIGIN + "?q=" + parameter + "&sort=stars&order=desc&per_page=15"
        val target = WorkspaceAgentReachPolicy.parse(url)
        require(target.host == "api.github.com" &&
            target.platform == WorkspaceAgentReachPolicy.Platform.GITHUB) {
            "GitHub repository search escaped public GitHub"
        }
        return Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "LYRA-RepositorySearch/1")
            .get()
            .build()
    }

    private fun compact(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]"""), "")

    private fun nameSegments(name: String): Set<String> =
        name.replace(Regex("""(?<=[a-z0-9])(?=[A-Z])"""), " ")
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .map { it.lowercase(Locale.ROOT) }.filter(String::isNotBlank).toSet()

    /**
     * A repository name should contain an actual project-name segment (or exact
     * normalized full name), not merely its letters buried inside a longer word.
     * Applied to both public GitHub API results and DuckDuckGo fallback results.
     */
    internal fun nameMatchesQuery(url: String, query: String): Boolean {
        if (!WorkspacePublicWebSearch.isGitHubRepositoryUrl(url)) return false
        val name = URI(url).path.trim('/').substringAfter('/')
        val terms = projectTerms(query)
        if (terms.isEmpty()) return false
        val segments = nameSegments(name)
        return compact(name) == compact(terms.joinToString("")) ||
            terms.any { it in segments }
    }

    /** Testable deterministic ranking of repository metadata returned by the real GitHub API. */
    internal fun parseJson(
        json: String,
        request: WorkspaceWebLinkIntent.Request,
    ): List<WorkspacePublicWebSearch.Candidate> {
        require(supports(request))
        val root = JSONObject(json)
        val items = root.optJSONArray("items") ?: return emptyList()
        val terms = projectTerms(request.query)
        val soughtName = compact(terms.joinToString(""))
        if (soughtName.length < 2) return emptyList()
        return buildList {
            for (i in 0 until minOf(items.length(), 15)) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optBoolean("private", false)) continue
                val name = item.optString("name").trim()
                val fullName = item.optString("full_name").trim()
                val url = item.optString("html_url").trim()
                if (fullName.split('/').size != 2 ||
                    !fullName.endsWith("/" + name, ignoreCase = true) ||
                    !WorkspacePublicWebSearch.isGitHubRepositoryUrl(url)) continue
                // The exact GitHub API repository identity must match the browser URL.
                if (!url.equals("https://github.com/" + fullName, ignoreCase = true)) continue
                val normalName = compact(name)
                val segments = nameSegments(name)
                val overlap = terms.count { it in segments }
                if (!nameMatchesQuery(url, request.query)) continue
                val nameRank = when {
                    normalName == soughtName -> 3000
                    segments.containsAll(terms.toSet()) -> 1400
                    else -> overlap * 250
                }
                val stars = item.optLong("stargazers_count", 0L).coerceAtLeast(0L)
                val popularity = (log10(stars.toDouble() + 1.0) * 105)
                    .toInt().coerceAtMost(650)
                val penalty = (if (item.optBoolean("fork", false)) 600 else 0) +
                    (if (item.optBoolean("archived", false)) 450 else 0)
                val description = item.optString("description")
                    .takeUnless { it == "null" }.orEmpty().trim().take(250)
                add(
                    WorkspacePublicWebSearch.Candidate(
                        title = fullName + " — GitHub repository",
                        url = url,
                        snippet = description,
                        score = nameRank + popularity - penalty,
                    ),
                )
            }
        }.distinctBy { it.url }.sortedByDescending { it.score }.take(8)
    }

    fun readSearch(
        response: Response,
        request: WorkspaceWebLinkIntent.Request,
    ): List<WorkspacePublicWebSearch.Candidate> {
        require(response.code == 200) { "GitHub public repository search unavailable" }
        val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
        require(contentType.contains("json")) { "GitHub search did not return JSON" }
        val data = response.peekBody(MAX_SEARCH_BYTES + 1).bytes()
        require(data.isNotEmpty() && data.size <= MAX_SEARCH_BYTES) {
            "GitHub repository search exceeded bounded response size"
        }
        return parseJson(String(data, Charsets.UTF_8), request)
    }
}
