package com.myra.assistant.ui.workspace

/**
 * Presentation-only parser for explicit verified GitHub Actions run links.
 * It never decides which tool to call or grants repository/write authority.
 */
internal object WorkspaceVerifiedChatLinks {
    data class Link(val range: IntRange, val label: String, val url: String)

    private val action = Regex(
        """\[([^\[\]\n]{1,80})\]\((https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(?:actions/runs/[1-9][0-9]{0,18}|releases/download/airi-memory-[0-9a-f]{12}/lyra-phone-test\.apk))\)"""
    )

    fun find(line: String): List<Link> = action.findAll(line).map { match ->
        Link(match.range, match.groupValues[1], match.groupValues[2])
    }.toList()
}
