package com.myra.assistant.ui.workspace

/**
 * Presentation-only parser for safe public assistant links.
 *
 * It never grants read/write authority or verifies the truth of a model-supplied destination.
 * GitHub build/release links may already be independently verified by their producer; every other
 * destination is admitted only as a syntactically safe public HTTPS link.
 */
internal object WorkspaceVerifiedChatLinks {
    data class Link(val range: IntRange, val label: String, val url: String)

    private val markdown = Regex(
        """\[([^\[\]\n]{1,120})\]\((https://[^\s<>\n)]{1,2048})\)"""
    )
    private val bare = Regex("""https://[^\s<>\[\]()"']{1,2048}""")

    private fun safeUrl(raw: String): String? {
        val clean = raw.trim()
        val target = runCatching { WorkspaceAgentReachPolicy.parse(clean) }.getOrNull() ?: return null
        return buildString {
            append(target.canonicalUrl)
            target.fragment?.takeIf(String::isNotBlank)?.let { append('#').append(it) }
        }
    }

    fun find(line: String): List<Link> {
        if (line.isBlank()) return emptyList()

        val markdownLinks = markdown.findAll(line).mapNotNull { match ->
            val url = safeUrl(match.groupValues[2]) ?: return@mapNotNull null
            Link(match.range, match.groupValues[1], url)
        }.toList()

        val occupied = markdownLinks.map(Link::range)
        val bareLinks = bare.findAll(line).mapNotNull { match ->
            if (occupied.any { match.range.first <= it.last && it.first <= match.range.last }) {
                return@mapNotNull null
            }
            val raw = match.value.trimEnd('.', ',', ';', '!', ':')
            if (raw.isBlank()) return@mapNotNull null
            val start = match.range.first
            val range = start until (start + raw.length)
            val url = safeUrl(raw) ?: return@mapNotNull null
            Link(range, raw, url)
        }.toList()

        return (markdownLinks + bareLinks).sortedBy { it.range.first }
    }
}
