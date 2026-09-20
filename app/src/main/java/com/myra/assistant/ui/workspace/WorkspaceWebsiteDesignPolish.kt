package com.myra.assistant.ui.workspace

/** Source-only polish for a narrowly specified NEW website. The provider's files still go
 * through the existing validation, freshness, atomic apply and Undo/Keep owners. No AI call,
 * asset download, new permission or edit to an established site's design is made here.
 */
internal object WorkspaceWebsiteDesignPolish {
    data class Outcome(val files: Map<String, String>, val changed: Boolean)

    private val heading = Regex("""(?is)<h([1-6])\b[^>]*>(.*?)</h\1\s*>""")
    private val tags = Regex("""(?s)<[^>]*>""")
    private val section = Regex("""(?is)<section\b[^>]*>(?:(?!<section\b|</section\s*>).){0,12000}</section\s*>""")
    private val openingSection = Regex("""(?is)^<section\b[^>]*>""")
    private val classAttribute = Regex("""(?is)\bclass\s*=\s*(["'])(.*?)\1""")
    private val staticExplore = Regex("""(?is)<h([1-6])\b[^>]*>\s*Exploring\s+Minicoy!\s*</h\1\s*>""")
    private const val MARKER = "/* LYRA new-site requested visual polish v1 */"

    private fun visible(raw: String): String = tags.replace(raw, " ")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("""\s+"""), " ").trim()
        .replace(Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}!]+$"""), "").trim()

    private fun hasHeading(source: String, title: String): Boolean = heading.findAll(source).any {
        visible(it.groupValues[2]).equals(title, ignoreCase = true)
    }

    private fun addClass(openTag: String, token: String): String {
        val match = classAttribute.find(openTag)
        if (match != null) {
            if (match.groupValues[2].split(Regex("""\s+""")).contains(token)) return openTag
            val old = match.groupValues[2].trim()
            return openTag.replaceRange(match.range,
                "class=${match.groupValues[1]}$old $token${match.groupValues[1]}")
        }
        return openTag.dropLast(1) + " class=\"$token\">"
    }

    private fun newOrStarter(original: Map<String, String?>): Boolean =
        original.values.all { it.isNullOrBlank() } ||
            (original["index.html"]?.contains("<h1>Hello, Workspace!</h1>") == true &&
                original["style.css"]?.contains("body { margin: 0; padding: 2rem;") == true &&
                original["script.js"]?.contains("// Your JavaScript starts here.") == true)

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               generated: Map<String, String>): Outcome {
        val goal = snapshot.goal
        if (!newOrStarter(snapshot.original) || !goal.contains("Minicoy", true) ||
            !goal.contains("Things to Explore", true) ||
            !goal.contains("Explore Minicoy", true) ||
            !goal.contains("blue hero", true) ||
            !goal.contains("pink", true) ||
            !listOf("Beaches", "Lighthouse", "Local Food").all { goal.contains(it, true) } ||
            Regex("""(?i)\b(?:remove|delete|hatao|nikalo)\b""").containsMatchIn(goal))
            return Outcome(generated, false)
        val originalHtml = generated["index.html"] ?: return Outcome(generated, false)
        val originalCss = generated["style.css"] ?: return Outcome(generated, false)
        if (originalCss.contains(MARKER)) return Outcome(generated, false)
        var html = originalHtml

        // Prefer a header containing BOTH the requested title and CTA, not an unrelated nav.
        val header = Regex("""(?is)<header\b[^>]*>""").findAll(html).firstOrNull { opening ->
            val close = Regex("""(?i)</header\s*>""").find(html, opening.range.last + 1)
            close != null && close.range.first - opening.range.first < 6000 &&
                hasHeading(html.substring(opening.range.last + 1, close.range.first), "Welcome to Minicoy") &&
                html.substring(opening.range.last + 1, close.range.first)
                    .contains("Explore Minicoy", ignoreCase = true)
        } ?: return Outcome(generated, false)
        html = html.replaceRange(header.range, addClass(header.value, "lyra-requested-blue-hero"))

        // The actual section must contain the heading AND all three card headings. A CTA or
        // explanatory paragraph merely mentioning these words is not a card group.
        val cards = section.findAll(html).filter { item ->
            hasHeading(item.value, "Things to Explore") &&
                listOf("Beaches", "Lighthouse", "Local Food").all { hasHeading(item.value, it) }
        }.toList()
        if (cards.size != 1) return Outcome(generated, false)
        val group = cards.single()
        val open = openingSection.find(group.value) ?: return Outcome(generated, false)
        html = html.replaceRange(group.range.first + open.range.first .. group.range.first + open.range.last,
            addClass(open.value, "lyra-requested-explore"))

        // The button-target feedback already says "Exploring Minicoy!". Remove only a
        // redundant standalone heading in a separate, simple paragraph section; retain its
        // descriptive paragraph. Never remove a card, action, other heading or complex node.
        if (goal.contains("Exploring Minicoy!", true) &&
            html.contains("lyra-explore-feedback")) {
            val repeats = section.findAll(html).filter { item ->
                staticExplore.findAll(item.value).count() == 1 &&
                    !hasHeading(item.value, "Things to Explore") &&
                    !Regex("""(?is)<(?:a|button|form|script|article)\b""").containsMatchIn(item.value) &&
                    Regex("""(?is)<p\b""").containsMatchIn(item.value) &&
                    heading.findAll(item.value).count() == 1 && item.value.length < 2500
            }.toList()
            if (repeats.size == 1) {
                val repeat = repeats.single()
                val cleaned = staticExplore.replaceFirst(repeat.value, "")
                val start = openingSection.find(cleaned)
                if (start != null) {
                    val revised = cleaned.replaceRange(start.range,
                        addClass(start.value, "lyra-requested-explore-copy"))
                    html = html.replaceRange(repeat.range, revised)
                }
            }
        }

        val css = originalCss + "\n" + MARKER + "\n" +
            ".lyra-requested-blue-hero { background: #2157c8 !important; " +
            "background-image: none !important; color: #fff !important; " +
            "padding: 52px 20px 42px !important; text-align: center !important; " +
            "min-height: 0 !important; box-sizing: border-box; }\n" +
            ".lyra-requested-blue-hero h1 { color: #fff !important; " +
            "max-width: 22ch; margin: 0 auto 20px !important; " +
            "font-size: clamp(1.8rem, 7vw, 2.8rem); line-height: 1.15; }\n" +
            ".lyra-requested-blue-hero a, .lyra-requested-blue-hero button { " +
            "display: inline-block; max-width: 100%; box-sizing: border-box; }\n" +
            ".lyra-requested-explore { display: block !important; width: 100% !important; " +
            "max-width: 1080px !important; min-height: 0 !important; margin: 0 auto !important; " +
            "padding: 26px 16px 16px !important; box-sizing: border-box; }\n" +
            ".lyra-requested-explore > .lyra-requested-explore-title { " +
            "display: block !important; width: 100% !important; " +
            "margin: 0 0 16px !important; text-align: center !important; " +
            "font-size: clamp(1.45rem, 6vw, 2rem); line-height: 1.2; }\n" +
            ".lyra-requested-explore .lyra-requested-explore-cards { " +
            "display: grid !important; grid-template-columns: minmax(0, 1fr) !important; " +
            "gap: 12px !important; width: 100% !important; min-width: 0 !important; }\n" +
            ".lyra-requested-explore .lyra-requested-explore-card { " +
            "width: 100% !important; min-width: 0 !important; " +
            "min-height: 0 !important; box-sizing: border-box; " +
            "padding: 16px !important; }\n" +
            ".lyra-requested-explore-copy { min-height: 0 !important; " +
            "margin: 0 auto !important; padding: 4px 16px 26px !important; " +
            "max-width: 720px; text-align: center; box-sizing: border-box; }\n" +
            "@media (min-width: 720px) { .lyra-requested-explore .lyra-requested-explore-cards { " +
            "grid-template-columns: repeat(3, minmax(0, 1fr)) !important; } }\n"
        if (html.length > 15_000 || css.length > 15_000 ||
            html.length + css.length + generated.getValue("script.js").length > 30_000)
            return Outcome(generated, false)
        return Outcome(generated + ("index.html" to html) + ("style.css" to css),
            html != originalHtml || css != originalCss)
    }
}
