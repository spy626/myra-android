package com.myra.assistant.ui.workspace

import java.net.URI

/**
 * Display-only projection of runtime-verified Sources data.
 * Never changes persisted metadata, grants access, or calls a link "official".
 */
internal object WorkspaceSourcePresentation {
    data class Display(
        val title: String,
        val url: String,
        val domainAndStatus: String,
        val description: String,
    )

    /** Shorten at a word boundary without leaving a half-word at the end. */
    internal fun shorten(raw: String, maxCharacters: Int): String {
        require(maxCharacters >= 8)
        val cleaned = raw.replace(Regex("""\s+"""), " ").trim()
        if (cleaned.length <= maxCharacters) return cleaned
        val prefix = cleaned.take(maxCharacters - 1)
        val boundary = prefix.lastIndexOf(' ')
        return (if (boundary >= maxCharacters / 2) {
            prefix.substring(0, boundary).trimEnd()
        } else prefix.trimEnd()) + "…"
    }

    fun display(source: WorkspaceVerifiedSourceStore.Source): Display {
        val target = WorkspaceAgentReachPolicy.parse(source.url)
        val title = if (WorkspacePublicWebSearch.isGitHubRepositoryUrl(target.canonicalUrl)) {
            val path = URI(target.canonicalUrl).path.trim('/')
            "$path — GitHub repository"
        } else shorten(source.title, 80)

        val status = source.verifiedLabel?.trim().orEmpty()
        val meta = target.host.removePrefix("www.") +
            if (status.isBlank()) "" else "  ·  " + status

        return Display(
            title = title,
            url = target.canonicalUrl,
            domainAndStatus = meta,
            description = shorten(source.snippet, 205),
        )
    }

    /** Single-source sheets wrap their content; long collections scroll at this cap. */
    fun maxScrollHeightPx(screenHeightPx: Int): Int =
        (screenHeightPx * 0.62f).toInt().coerceAtLeast(1)
}
