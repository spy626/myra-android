package com.myra.assistant.ui.workspace

/**
 * Mobile-first presentation of Markdown chat prose, without changing stored messages,
 * recommendation meaning, click destinations, execution authority or copy source.
 */
internal object WorkspaceMarkdownLayout {
    enum class Kind { PLAIN, HEADING, BULLET, NUMBERED, TABLE_TITLE, TABLE_ROW }
    data class Line(val text: String, val kind: Kind = Kind.PLAIN)

    private val heading = Regex("""^\s{0,3}#{1,3}\s+\S.*$""")
    private val bullet = Regex("""^\s{0,3}[-*+]\s+(.+)$""")
    private val numbered = Regex("""^\s{0,3}(\d{1,2})[.)]\s+(.+)$""")
    private val divider = Regex("""^:?-{3,}:?$""")

    private fun cells(value: String): List<String>? {
        val line = value.trim()
        if (!line.startsWith("|") || !line.endsWith("|")) return null
        val pieces = line.drop(1).dropLast(1).split('|').map(String::trim)
        return pieces.takeIf { it.size in 2..3 && it.all(String::isNotBlank) }
    }

    fun prepare(raw: String): List<Line> {
        val source = raw.lines()
        val output = mutableListOf<Line>()
        var index = 0
        var fenced = false
        fun add(value: Line) {
            if ((value.kind == Kind.HEADING || value.kind == Kind.NUMBERED) &&
                output.lastOrNull()?.text?.isNotBlank() == true
            ) output.add(Line(""))
            output.add(value)
        }
        while (index < source.size) {
            val original = source[index]
            if (original.trimStart().startsWith("~~~") || original.trimStart().startsWith("\u0060\u0060\u0060")) {
                fenced = !fenced
                output.add(Line(original))
                index++
                continue
            }
            if (!fenced && index + 1 < source.size) {
                val headers = cells(original)
                val separator = cells(source[index + 1])
                if (headers != null && separator != null &&
                    headers.size == separator.size && separator.all { divider.matches(it) }
                ) {
                    if (output.lastOrNull()?.text?.isNotBlank() == true) output.add(Line(""))
                    output.add(Line(headers.joinToString(" · "), Kind.TABLE_TITLE))
                    index += 2
                    while (index < source.size) {
                        val values = cells(source[index]) ?: break
                        if (values.size != headers.size) break
                        val detail = values.drop(1).mapIndexed { cellIndex, value ->
                            "**" + headers[cellIndex + 1] + ":** " + value
                        }.joinToString("  ·  ")
                        output.add(Line("**" + values[0] + "** — " + detail, Kind.TABLE_ROW))
                        index++
                    }
                    if (index < source.size && source[index].isNotBlank()) output.add(Line(""))
                    continue
                }
            }
            if (!fenced) {
                val bulletItem = bullet.matchEntire(original)
                val numberedItem = numbered.matchEntire(original)
                when {
                    heading.matches(original) -> add(Line(original, Kind.HEADING))
                    bulletItem != null -> add(Line("• " + bulletItem.groupValues[1], Kind.BULLET))
                    numberedItem != null -> add(Line(
                        numberedItem.groupValues[1] + ". " + numberedItem.groupValues[2], Kind.NUMBERED
                    ))
                    else -> output.add(Line(original))
                }
            } else output.add(Line(original))
            index++
        }
        return output
    }
}
