package com.myra.assistant.ui.workspace

/**
 * Readable native Markdown for deterministic link results. Formatting only: it never
 * authorizes links or upgrades model-generated URLs into verified sources.
 */
internal object WorkspaceVerifiedLinkReply {
    private fun plain(value: String, limit: Int): String =
        WorkspaceSourcePresentation.shorten(
            value.replace(Regex("""[\r\n\t*_#\[\]<>]"""), " "),
            limit,
        )

    fun format(title: String, url: String, summary: String, fallback: String): String {
        val target = WorkspaceAgentReachPolicy.parse(url)
        val destination = target.canonicalUrl
        val headline = plain(title, 110).ifBlank { target.host }
        val detail = plain(summary, 190).ifBlank { plain(fallback, 190) }
        // Visible full URL, not a destination hidden behind an ambiguous short label.
        return "**" + headline + "**\n\n" +
            "🔗 [" + destination + " ↗](" + destination + ")\n\n" + detail
    }
}
