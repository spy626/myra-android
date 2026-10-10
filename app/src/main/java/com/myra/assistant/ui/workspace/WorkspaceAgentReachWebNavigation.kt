package com.myra.assistant.ui.workspace

import java.net.URI
import java.util.Locale

/**
 * Bounded native observe -> choose -> verify policy for ONE read-only same-site link.
 * Link candidates must originate in the actually fetched static HTML. The current USER
 * request supplies the topic; web content is evidence only, never commands or authority.
 * No extra model call, site-specific script, user session, or autonomous form/browser click.
 */
internal object WorkspaceAgentReachWebNavigation {
    data class Choice(
        val link: WorkspaceAgentReachPublicWeb.Link,
        val target: WorkspaceAgentReachPolicy.Target,
        val matchedTerms: List<String>,
    )

    data class Journey(
        val primary: WorkspaceAgentReachPublicWeb.Page,
        val followed: WorkspaceAgentReachPublicWeb.Page? = null,
        val selected: Choice? = null,
        val followUpStatus: String = "No relevant safe same-site link was selected",
        val analysis: WorkspaceAgentReachSourceAnalysis.Report? = null,
    )

    private val nonTopic = setOf(
        "https", "http", "www", "com", "org", "net", "read", "check", "review",
        "inspect", "open", "analyze", "analyse", "analysis", "summarize",
        "summarise", "explain", "website", "webpage", "site", "page", "link",
        "this", "that", "from", "with", "about", "please", "karo", "karna",
        "kro", "dekho", "dekhe", "bro", "mujhe", "isko", "usko", "batao",
        "details", "detail", "information", "public", "find", "look", "follow",
        "links", "research", "understand", "entire", "whole", "full",
    )
    private val unsafePath = Regex(
        """(?i)(?:^|[/_.-])(?:login|logout|logoff|signout|signin|register|signup|""" +
            """delete|remove|unsubscribe|checkout|purchase|cart|order|account|""" +
            """admin|payment|pay|settings|download|install|auth|oauth|redirect|""" +
            """callback|subscribe|post|submit|vote)(?:$|[/_.-])"""
    )
    private val unsafeExtension = Regex(
        """(?i)\.(?:pdf|zip|tar|gz|apk|exe|dmg|mp3|mp4|avi|png|jpg|jpeg|webp|""" +
            """json|csv|xml|js|css|woff2?)$"""
    )

    private val noFollow = Regex(
        """(?iu)\b(?:do\s+not|don't|dont|never)\s+(?:follow|navigate|visit|explore)\b|""" +
            """\b(?:follow|navigate|visit|explore)\s+mat\b|""" +
            """\bmat\s+(?:follow|navigate|visit|explore)\b|""" +
            """\b(?:only|just)\s+(?:this|the)\s+(?:page|link)\b"""
    )

    fun choose(
        primary: WorkspaceAgentReachPublicWeb.Page,
        userRequest: String,
    ): Choice? {
        if (noFollow.containsMatchIn(userRequest)) return null
        val origin = runCatching {
            WorkspaceAgentReachPolicy.parse(primary.evidence.provenance.finalUrl)
        }.getOrNull() ?: return null
        if (origin.platform == WorkspaceAgentReachPolicy.Platform.GITHUB) return null
        val withoutUrls = userRequest.replace(Regex("""(?i)https://[^\s<>"']+"""), " ")
        val topic = Regex("""[\p{L}\p{N}]{4,}""")
            .findAll(withoutUrls.lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot { it in nonTopic }
            .distinct().take(10).toList()
        if (topic.isEmpty()) return null // Bare URL/general request is NOT crawl authority.

        return primary.observedLinks.mapIndexedNotNull { index, link ->
            val target = runCatching { WorkspaceAgentReachPolicy.parse(link.url) }
                .getOrNull() ?: return@mapIndexedNotNull null
            val path = runCatching { URI(target.canonicalUrl).path.orEmpty() }
                .getOrDefault("")
            if (target.host != origin.host || target.platform != origin.platform ||
                target.canonicalUrl == origin.canonicalUrl ||
                URI(target.canonicalUrl).rawQuery != null ||
                path.isBlank() || path == "/" || unsafePath.containsMatchIn(path) ||
                unsafeExtension.containsMatchIn(path) ||
                unsafePath.containsMatchIn(link.label.replace(' ', '-'))
            ) return@mapIndexedNotNull null

            val labelTokens = Regex("""[\p{L}\p{N}]{4,}""")
                .findAll(link.label.lowercase(Locale.ROOT)).map { it.value }.toSet()
            val pathTokens = Regex("""[\p{L}\p{N}]{4,}""")
                .findAll(path.lowercase(Locale.ROOT)).map { it.value }.toSet()
            val matched = topic.filter { it in labelTokens || it in pathTokens }
            val score = matched.sumOf {
                (if (it in labelTokens) 3 else 0) + (if (it in pathTokens) 1 else 0)
            }
            if (score <= 0) null else Triple(index, score, Choice(link, target, matched))
        }.sortedWith(compareByDescending<Triple<Int, Int, Choice>> { it.second }
            .thenBy { it.first })
            .firstOrNull()?.third
    }

    fun verify(
        primary: WorkspaceAgentReachPublicWeb.Page,
        selected: Choice,
        followed: WorkspaceAgentReachPublicWeb.Page,
    ): Journey {
        val p = primary.evidence.provenance
        val f = followed.evidence.provenance
        val root = WorkspaceAgentReachPolicy.parse(p.finalUrl)
        val final = WorkspaceAgentReachPolicy.parse(f.finalUrl)
        require(primary.observedLinks.any { it == selected.link }) {
            "Navigated target was not in the observed source page"
        }
        require(selected.link.url == selected.target.canonicalUrl &&
            f.requestedUrl == selected.target.canonicalUrl &&
            final.host == root.host && final.platform == root.platform &&
            selected.target.host == root.host && selected.target.platform == root.platform &&
            followed.excerpt.isNotBlank() && f.contentSha256.isNotBlank()) {
            "Follow-up source/provenance verification failed"
        }
        return Journey(primary, followed, selected, "Verified one relevant same-site static page")
    }
}
