package com.myra.assistant.ui.workspace

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * App download discovery is separate from connected GitHub build downloads.
 * Public Apple catalog + public Google Play search; never executes or installs anything.
 * Only actually reachable store URLs and publisher-linked websites become Sources.
 */
internal object WorkspaceAppDownloadSearch {
    enum class Kind { ANDROID, IPHONE, PUBLISHER }
    data class Result(
        val kind: Kind,
        val appName: String,
        val url: String,
        val detail: String,
        val label: String,
    )
    data class AppleListing(
        val name: String,
        val seller: String,
        val storeUrl: String,
        val sellerUrl: String?,
        val bundleId: String,
    )
    private const val MAX_JSON = 256_000L
    private val validPackage = Regex("""[A-Za-z][\w]*(?:\.[A-Za-z][\w]*){1,9}""")

    private fun appTitle(value: String): String = value
        .substringBefore(" - Apps on Google Play")
        .substringBefore(" on the App Store")
        .substringBefore(" - App Store")
        .substringBefore(':')
        .substringBefore(" — ")
        .substringBefore(" - ")
        .trim()

    internal fun storeNameMatches(request: String, found: String): Boolean =
        WorkspaceAppDownloadIntent.nameMatches(request, found) ||
            WorkspaceAppDownloadIntent.nameMatches(request, appTitle(found))

    fun appleRequest(name: String, country: String = "in"): Request {
        require(name.length in 2..90 && country in setOf("in", "us"))
        val term = URLEncoder.encode(name, StandardCharsets.UTF_8.name())
        val url = "https://itunes.apple.com/search?term=" + term +
            "&media=software&entity=software&limit=15&country=" + country
        require(WorkspaceAgentReachPolicy.parse(url).host == "itunes.apple.com")
        return Request.Builder().url(url)
            .header("Accept", "application/json, text/javascript")
            .header("User-Agent", "LYRA-PublicAppLookup/1").get().build()
    }

    internal fun appleUrl(url: String): Boolean {
        val target = runCatching { WorkspaceAgentReachPolicy.parse(url) }.getOrNull()
            ?: return false
        if (target.host !in setOf("apps.apple.com", "itunes.apple.com")) return false
        val path = URI(target.canonicalUrl).path.orEmpty()
        return path.contains("/app/") && Regex("""(?:^|/)id\d{5,15}$""").containsMatchIn(path)
    }

    internal fun playUrl(url: String): Boolean {
        val target = runCatching { WorkspaceAgentReachPolicy.parse(url) }.getOrNull()
            ?: return false
        if (target.host != "play.google.com") return false
        val uri = URI(target.canonicalUrl)
        if (uri.path != "/store/apps/details") return false
        val ids = uri.rawQuery.orEmpty().split('&')
            .filter { it.startsWith("id=") }.map { it.substringAfter("id=") }
        return ids.size == 1 && validPackage.matches(ids.single())
    }

    internal fun parseApple(json: String, app: String): AppleListing? {
        val items = JSONObject(json).optJSONArray("results") ?: return null
        val choices = buildList {
            for (i in 0 until minOf(15, items.length())) {
                val data = items.optJSONObject(i) ?: continue
                val title = data.optString("trackName").trim()
                val url = data.optString("trackViewUrl").trim()
                val seller = data.optString("sellerName").trim()
                if (!storeNameMatches(app, title) || !appleUrl(url) ||
                    seller.isBlank() || data.optString("kind") != "software") continue
                val publisher = data.optString("sellerUrl").trim()
                    .takeIf {
                        it.isNotBlank() &&
                            runCatching { WorkspaceAgentReachPolicy.parse(it) }.isSuccess
                    }
                val listing = AppleListing(
                    title, seller, WorkspaceAgentReachPolicy.parse(url).canonicalUrl,
                    publisher, data.optString("bundleId").trim(),
                )
                val score = if (WorkspaceAppDownloadIntent.compact(title) ==
                    WorkspaceAppDownloadIntent.compact(app)) 100 else 50
                add(score to listing)
            }
        }
        return choices.sortedByDescending { it.first }.firstOrNull()?.second
    }

    fun readApple(response: Response, app: String): AppleListing? {
        require(response.code == 200) { "Apple catalog lookup unavailable" }
        val content = response.peekBody(MAX_JSON + 1).bytes()
        require(content.isNotEmpty() && content.size <= MAX_JSON) {
            "Apple app metadata too large or missing"
        }
        return parseApple(String(content, Charsets.UTF_8), app)
    }

    fun googleSearch(app: String): Request =
        WorkspacePublicWebSearch.searchRequest(
            WorkspaceWebLinkIntent.Request("$app app", preferredHost = "play.google.com")
        )

    internal fun parseGoogle(html: String, name: String): List<WorkspacePublicWebSearch.Candidate> =
        WorkspacePublicWebSearch.parseHtml(html, "$name app", "play.google.com")
            .filter { playUrl(it.url) && storeNameMatches(name, it.title) }
            .take(4)

    fun readGoogle(response: Response, name: String): List<WorkspacePublicWebSearch.Candidate> {
        require(response.isSuccessful)
        require(response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            .startsWith("text/html"))
        val data = response.peekBody(768_001).bytes()
        require(data.isNotEmpty() && data.size <= 768_000)
        return parseGoogle(String(data, Charsets.UTF_8), name)
    }

    /** Redirects can change locale, never the app/package or publisher identity. */
    internal fun sameStoreIdentity(original: String, redirected: String, kind: Kind): Boolean {
        if (kind == Kind.PUBLISHER) {
            val from = safePublisherWebsite(original) ?: return false
            val to = safePublisherWebsite(redirected) ?: return false
            val oldHost = URI(from).host.removePrefix("www.")
            val newHost = URI(to).host.removePrefix("www.")
            return oldHost == newHost || newHost.endsWith("." + oldHost)
        }
        val a = URI(original)
        val b = URI(redirected)
        return when (kind) {
            Kind.IPHONE -> appleUrl(original) && appleUrl(redirected) &&
                Regex("""id\d{5,15}$""").find(a.path)?.value ==
                    Regex("""id\d{5,15}$""").find(b.path)?.value
            Kind.ANDROID -> playUrl(original) && playUrl(redirected) &&
                a.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("id=") } ==
                    b.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("id=") }
            Kind.PUBLISHER -> false
        }
    }

    internal fun safePublisherWebsite(url: String?): String? {
        if (url == null) return null
        val target = runCatching { WorkspaceAgentReachPolicy.parse(url) }.getOrNull()
            ?: return null
        if (target.host in setOf(
                "apps.apple.com", "itunes.apple.com", "play.google.com",
                "github.com", "localhost",
            )) return null
        return target.canonicalUrl
    }

    fun reply(name: String, results: List<Result>): String = buildString {
        appendLine("**" + name + " — app download links**")
        appendLine()
        if (results.isEmpty()) {
            append("Bro, is app ke trusted download destinations abhi confirm nahi hue. " +
                "Random APK ya guessed store links nahi dunga.")
            return@buildString
        }
        for (result in results) {
            val title = when (result.kind) {
                Kind.ANDROID -> "📱 **Android · Google Play**"
                Kind.IPHONE -> "🍎 **iPhone · App Store**"
                Kind.PUBLISHER -> "🌐 **Publisher website**"
            }
            appendLine(title)
            appendLine("[" + result.url + " ↗](" + result.url + ")")
            appendLine(result.detail)
            appendLine()
        }
        val missing = Kind.entries.filter { kind -> results.none { it.kind == kind } }
        if (missing.isNotEmpty()) {
            append("Not confirmed: " + missing.joinToString(", ") {
                when (it) {
                    Kind.ANDROID -> "Android"
                    Kind.IPHONE -> "iPhone"
                    Kind.PUBLISHER -> "publisher website"
                }
            } + ".")
        }
    }.trim()

    fun sources(results: List<Result>): List<WorkspaceVerifiedSourceStore.Source> =
        results.map {
            WorkspaceVerifiedSourceStore.Source(
                title = it.appName + " — " + when(it.kind) {
                    Kind.ANDROID -> "Google Play Store"
                    Kind.IPHONE -> "Apple App Store"
                    Kind.PUBLISHER -> "Publisher website"
                },
                url = it.url,
                snippet = it.detail,
                observedAtMs = System.currentTimeMillis(),
                verifiedLabel = it.label,
            )
        }
}

internal class WorkspaceAppDownloadRunner(private val listener: Listener) {
    interface Listener {
        fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String? = null)
        fun onComplete(name: String, results: List<WorkspaceAppDownloadSearch.Result>)
    }

    private var generation = 0L
    private var active: Call? = null

    @Synchronized fun cancel() {
        generation++
        active?.cancel()
        active = null
    }

    @Synchronized fun start(request: WorkspaceAppDownloadIntent.Request) {
        cancel()
        val run = generation
        val results = mutableListOf<WorkspaceAppDownloadSearch.Result>()
        listener.onEvent(WorkspaceWorkPhase.SEARCHING, "Searching Apple App Store", request.appName)
        searchApple(run, request.appName, "in", results)
    }

    private fun searchApple(
        run: Long,
        app: String,
        country: String,
        results: MutableList<WorkspaceAppDownloadSearch.Result>,
    ) {
        dispatch(run, WorkspaceAppDownloadSearch.appleRequest(app, country),
            success = { response ->
                val found = response.use {
                    runCatching { WorkspaceAppDownloadSearch.readApple(it, app) }.getOrNull()
                }
                if (found != null) {
                    verify(run, found.storeUrl, { WorkspaceAppDownloadSearch.sameStoreIdentity(
                        found.storeUrl, it, WorkspaceAppDownloadSearch.Kind.IPHONE
                    ) }) { target ->
                        if (target != null) {
                            results.add(WorkspaceAppDownloadSearch.Result(
                                WorkspaceAppDownloadSearch.Kind.IPHONE,
                                found.name,
                                target,
                                "Apple catalog listing · Seller: " + found.seller,
                                "Apple catalog listing · reachable",
                            ))
                        }
                        searchAndroid(run, app, results, found)
                    }
                } else if (country == "in") {
                    searchApple(run, app, "us", results)
                } else {
                    searchAndroid(run, app, results, null)
                }
            },
            failure = {
                if (country == "in") searchApple(run, app, "us", results)
                else searchAndroid(run, app, results, null)
            })
    }

    private fun searchAndroid(
        run: Long,
        app: String,
        results: MutableList<WorkspaceAppDownloadSearch.Result>,
        apple: WorkspaceAppDownloadSearch.AppleListing?,
    ) {
        listener.onEvent(WorkspaceWorkPhase.SEARCHING, "Searching Google Play", app)
        dispatch(run, WorkspaceAppDownloadSearch.googleSearch(app),
            success = { response ->
                val choices = response.use {
                    runCatching { WorkspaceAppDownloadSearch.readGoogle(it, app) }
                        .getOrDefault(emptyList())
                }
                verifyGoogle(run, app, choices, 0, results, apple)
            },
            failure = { searchPublisher(run, app, results, apple) })
    }

    private fun verifyGoogle(
        run: Long, app: String,
        choices: List<WorkspacePublicWebSearch.Candidate>, index: Int,
        results: MutableList<WorkspaceAppDownloadSearch.Result>,
        apple: WorkspaceAppDownloadSearch.AppleListing?,
    ) {
        if (index >= choices.size) {
            searchPublisher(run, app, results, apple)
            return
        }
        val choice = choices[index]
        verify(run, choice.url, { WorkspaceAppDownloadSearch.sameStoreIdentity(
            choice.url, it, WorkspaceAppDownloadSearch.Kind.ANDROID
        ) }) { target ->
            if (target != null) {
                results.add(WorkspaceAppDownloadSearch.Result(
                    WorkspaceAppDownloadSearch.Kind.ANDROID, app, target,
                    "Google Play listing · matched app name and package URL",
                    "Google Play listing · reachable",
                ))
                searchPublisher(run, app, results, apple)
            } else verifyGoogle(run, app, choices, index + 1, results, apple)
        }
    }

    private fun searchPublisher(
        run: Long,
        app: String,
        results: MutableList<WorkspaceAppDownloadSearch.Result>,
        apple: WorkspaceAppDownloadSearch.AppleListing?,
    ) {
        val website = WorkspaceAppDownloadSearch.safePublisherWebsite(apple?.sellerUrl)
        if (website == null) { finish(run, app, results); return }
        listener.onEvent(WorkspaceWorkPhase.VERIFYING, "Verifying publisher-linked site", app)
        verify(run, website, { WorkspaceAppDownloadSearch.sameStoreIdentity(
            website, it, WorkspaceAppDownloadSearch.Kind.PUBLISHER
        ) }) {
            if (it != null) {
                results.add(WorkspaceAppDownloadSearch.Result(
                    WorkspaceAppDownloadSearch.Kind.PUBLISHER, app, it,
                    "Publisher URL supplied by Apple's app catalog",
                    "Publisher-linked site · reachable",
                ))
            }
            finish(run, app, results)
        }
    }

    private fun verify(
        run: Long, url: String, acceptable: (String) -> Boolean,
        depth: Int = 0, done: (String?) -> Unit,
    ) {
        val target = runCatching { WorkspaceAgentReachPolicy.parse(url) }.getOrNull()
        if (target == null || !acceptable(target.canonicalUrl)) {
            done(null)
            return
        }
        dispatch(run, WorkspacePublicWebSearch.verifyRequest(target),
            success = { response ->
                response.use {
                    if (it.code in 300..399 && depth < 2) {
                        val next = runCatching {
                            WorkspacePublicWebSearch.redirect(target,
                                it.header("Location").orEmpty())
                        }.getOrNull()
                        if (next != null && acceptable(next.canonicalUrl)) {
                            verify(run, next.canonicalUrl, acceptable, depth + 1, done)
                        } else done(null)
                    } else {
                        val acceptablePage = runCatching {
                            WorkspacePublicWebSearch.verify(
                                it, WorkspacePublicWebSearch.Candidate(
                                    "Public app listing", target.canonicalUrl, "", 1,
                                ), target,
                            )
                        }.isSuccess
                        done(if (acceptablePage) target.canonicalUrl else null)
                    }
                }
            },
            failure = { done(null) })
    }

    private fun finish(
        run: Long, app: String,
        results: List<WorkspaceAppDownloadSearch.Result>,
    ) {
        synchronized(this) {
            if (run != generation) return
            active = null
            generation++
        }
        listener.onEvent(
            WorkspaceWorkPhase.DONE,
            results.size.toString() + " app destination(s) verified",
        )
        listener.onComplete(app, results.toList())
    }

    private fun dispatch(
        run: Long, request: Request,
        success: (Response) -> Unit,
        failure: () -> Unit,
    ) {
        synchronized(this) { if (run != generation) return }
        val call = WorkspaceAgentReachGitHub.client.newCall(request)
        synchronized(this) {
            if (run != generation) { call.cancel(); return }
            active = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@WorkspaceAppDownloadRunner) {
                    if (run != generation || active !== call) return
                    active = null
                }
                failure()
            }

            override fun onResponse(call: Call, response: Response) {
                synchronized(this@WorkspaceAppDownloadRunner) {
                    if (run != generation || active !== call) {
                        response.close()
                        return
                    }
                    active = null
                }
                runCatching { success(response) }.onFailure {
                    response.close()
                    failure()
                }
            }
        })
    }
}
