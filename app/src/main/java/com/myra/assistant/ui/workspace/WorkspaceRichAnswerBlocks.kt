package com.myra.assistant.ui.workspace

/**
 * Display-only Markdown block model for Chat replies. It reads the model's actual structure;
 * it NEVER generates answers, infers brands, changes the transcript or grants tool access.
 * Unknown / malformed syntax remains ordinary text. Only bounded assistant prose is parsed.
 */
internal object WorkspaceRichAnswerBlocks {
    sealed class Block {
        data class Paragraph(val text: String) : Block()
        data class Heading(val level: Int, val text: String) : Block()
        data class Bullets(val items: List<Bullet>) : Block()
        data class Numbered(val items: List<Step>) : Block()
        data class Table(val headers: List<String>, val rows: List<List<String>>) : Block()
        data class Quote(val text: String) : Block()
        object Divider : Block()
    }
    data class Bullet(val text: String, val depth: Int)
    data class Step(val number: String, val text: String, val details: List<String> = emptyList())

    private const val MAX_RICH_CHARS = 24_000
    private val heading = Regex("""^ {0,3}(#{1,3})\s+(.+?)\s*$""")
    private val bullet = Regex("""^( {0,6})[-*+]\s+(.+)$""")
    private val step = Regex("""^ {0,3}(\d{1,2})[.)]\s+(.+)$""")
    private val boldHeading = Regex("""^\s{0,3}\*\*(.{3,90}?)\*\*:?\s*$""")
    private val rule = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,})\s*$""")
    private val separator = Regex("""^:?-{3,}:?$""")

    private fun cells(raw: String): List<String>? {
        val value = raw.trim()
        if (!value.startsWith("|") || !value.endsWith("|")) return null
        val result = value.drop(1).dropLast(1).split('|').map { it.trim() }
        return result.takeIf { it.size in 2..3 && it.all { c -> c.isNotBlank() } }
    }

    /**
     * Some small free providers put an entire numbered plan on one physical line.
     * Break ONLY a consecutive sequence of actual numeric markers; no text is invented
     * or deleted and unnumbered / casual responses are untouched.
     */
    private fun unfoldInlineSteps(raw: String): String {
        val marker = Regex("""(?<![\p{L}\p{N}.])([1-6])[.)]\s+(?=\*\*|\p{L})""")
        val matches = marker.findAll(raw).toList()
        if (matches.size !in 2..6) return raw
        val values = matches.map { it.groupValues[1].toInt() }
        if (!values.zipWithNext().all { (a, b) -> b == a + 1 }) return raw
        val result = StringBuilder(raw)
        matches.asReversed().forEach { match ->
            val at = match.range.first
            if (at > 0 && raw[at - 1] != '\n') result.insert(at, '\n')
        }
        return result.toString()
    }

    fun parse(raw: String): List<Block> {
        if (raw.isBlank()) return emptyList()
        // An overly long pasted completion is kept intact rather than producing hundreds of views.
        if (raw.length > MAX_RICH_CHARS) return listOf(Block.Paragraph(raw))
        val lines = unfoldInlineSteps(raw.replace("\r\n", "\n")).lines()
        val output = mutableListOf<Block>()
        var i = 0
        fun special(index: Int): Boolean {
            if (index >= lines.size) return true
            val line = lines[index]
            if (line.isBlank() || heading.matches(line) || boldHeading.matches(line) ||
                rule.matches(line) ||
                bullet.matches(line) || step.matches(line) || line.trimStart().startsWith("> ")
            ) return true
            val a = cells(line)
            val b = lines.getOrNull(index + 1)?.let(::cells)
            return a != null && b != null && a.size == b.size &&
                b.all { separator.matches(it) }
        }
        while (i < lines.size) {
            val current = lines[i]
            if (current.isBlank()) { i++; continue }
            val h = heading.matchEntire(current)
            if (h != null) {
                output.add(Block.Heading(h.groupValues[1].length, h.groupValues[2]))
                i++
                continue
            }
            val standalone = boldHeading.matchEntire(current)
            if (standalone != null) {
                output.add(Block.Heading(3, standalone.groupValues[1].trim()))
                i++
                continue
            }
            if (rule.matches(current)) {
                output.add(Block.Divider)
                i++
                continue
            }
            val headers = cells(current)
            val sep = lines.getOrNull(i + 1)?.let(::cells)
            if (headers != null && sep != null && sep.size == headers.size &&
                sep.all { separator.matches(it) }
            ) {
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val values = cells(lines[i]) ?: break
                    if (values.size != headers.size) break
                    rows.add(values)
                    i++
                }
                if (rows.isEmpty()) output.add(Block.Paragraph(current + "\n" + lines[i - 1]))
                else output.add(Block.Table(headers, rows))
                continue
            }
            if (bullet.matches(current)) {
                val items = mutableListOf<Bullet>()
                while (i < lines.size) {
                    val b = bullet.matchEntire(lines[i]) ?: break
                    items.add(Bullet(b.groupValues[2], (b.groupValues[1].length / 2).coerceAtMost(3)))
                    i++
                }
                output.add(Block.Bullets(items))
                continue
            }
            if (step.matches(current)) {
                val items = mutableListOf<Step>()
                while (i < lines.size) {
                    val s = step.matchEntire(lines[i]) ?: break
                    val body = StringBuilder(s.groupValues[2])
                    i++
                    // Keep indented detail bullets inside the parent numbered step.
                    val details = mutableListOf<String>()
                    while (i < lines.size && lines[i].startsWith("  ") &&
                        body.length < 2500
                    ) {
                        val nested = bullet.matchEntire(lines[i])
                        if (nested != null && nested.groupValues[1].isNotEmpty()) {
                            details.add(nested.groupValues[2])
                            i++
                            continue
                        }
                        if (special(i)) break
                        body.append(' ').append(lines[i].trim())
                        i++
                    }
                    items.add(Step(s.groupValues[1], body.toString(), details))
                    // Blank lines between numbered steps are allowed.
                    var next = i
                    while (next < lines.size && lines[next].isBlank()) next++
                    if (next < lines.size && step.matches(lines[next])) i = next
                    else break
                }
                output.add(Block.Numbered(items))
                continue
            }
            if (current.trimStart().startsWith("> ")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("> ")) {
                    quoteLines.add(lines[i].trimStart().removePrefix("> "))
                    i++
                }
                output.add(Block.Quote(quoteLines.joinToString("\n")))
                continue
            }
            val prose = mutableListOf(current)
            i++
            while (i < lines.size && !special(i)) {
                prose.add(lines[i])
                i++
            }
            output.add(Block.Paragraph(prose.joinToString("\n")))
        }
        return output
    }

    fun isStructured(raw: String): Boolean = parse(raw).any {
        it !is Block.Paragraph
    }
}
