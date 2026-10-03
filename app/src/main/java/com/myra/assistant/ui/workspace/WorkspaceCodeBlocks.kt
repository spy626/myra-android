package com.myra.assistant.ui.workspace

/** Pure display parser: never edits the stored response, request or copied whole reply. */
internal object WorkspaceCodeBlocks {
    sealed class Part {
        data class Prose(val text: String) : Part()
        data class Code(val language: String, val source: String) : Part()
    }

    private val opening = Regex("^ {0,3}(`{3,}|~{3,})([A-Za-z0-9_+.#-]{0,24})[ \\t]*$")
    private const val MAX_CODE_CHARS = 80_000

    fun parse(raw: String): List<Part> {
        val lines = raw.split('\n')
        val parts = mutableListOf<Part>()
        var textStart = 0
        var i = 0
        while (i < lines.size) {
            val fence = opening.matchEntire(lines[i].removeSuffix("\r"))
            if (fence == null) { i++; continue }
            val marker = fence.groupValues[1]
            val closing = Regex("^ {0,3}${marker[0]}{${marker.length},}[ \\t]*$")
            var end = i + 1
            while (end < lines.size && !closing.matches(lines[end].removeSuffix("\r"))) end++
            // Never hide malformed or unfinished code: show the original Markdown as prose.
            if (end == lines.size) { i++; continue }
            val code = lines.subList(i + 1, end).joinToString("\n")
            if (code.isBlank() || code.length > MAX_CODE_CHARS) { i = end + 1; continue }
            val before = lines.subList(textStart, i).joinToString("\n").trim('\n')
            if (before.isNotBlank()) parts.add(Part.Prose(before))
            parts.add(Part.Code(fence.groupValues[2].lowercase(), code))
            i = end + 1
            textStart = i
        }
        val after = lines.subList(textStart, lines.size).joinToString("\n").trim('\n')
        if (after.isNotBlank()) parts.add(Part.Prose(after))
        return if (parts.any { it is Part.Code }) parts else listOf(Part.Prose(raw))
    }

    fun canPreview(block: Part.Code): Boolean =
        (block.language == "html" || block.language == "htm") &&
            block.source.length <= 50_000 &&
            (block.source.contains("<html", ignoreCase = true) ||
                block.source.contains("<!doctype html", ignoreCase = true))
}
