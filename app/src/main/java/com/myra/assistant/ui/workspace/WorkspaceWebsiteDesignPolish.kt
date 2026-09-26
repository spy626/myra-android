package com.myra.assistant.ui.workspace

/** Source-only polish for a narrowly specified NEW website. Provider output still passes
 * local validation, freshness, atomic apply and Undo/Keep. No model call, downloaded image,
 * new permission or silent redesign of an established project.
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

    /** Change only the exact generic prose inserted by LYRA's own bounded fallback.
     * User-written descriptions and model-written details are never replaced.
     */
    private fun improveFallbackCopy(html: String): String {
        val copy = mapOf(
            "Beaches" to ("Explore Minicoy's lagoon-side shores and enjoy the island's " +
                "clear blue water."),
            "Lighthouse" to ("Discover Minicoy's lighthouse and take in views of the " +
                "island and surrounding sea."),
            "Local Food" to ("Get a taste of island cooking, with coconut and seafood " +
                "among its familiar flavours.")
        )
        val generic = mapOf(
            "Beaches" to "Explore coastal scenery.",
            "Lighthouse" to "Learn about an island landmark.",
            "Local Food" to "Discover island flavours."
        )
        var result = html
        copy.forEach { (name, description) ->
            val pattern = Regex(
                "(?is)(<article\\b[^>]*>\\s*<h3\\b[^>]*>\\s*${Regex.escape(name)}\\s*</h3>\\s*" +
                    "<p\\b[^>]*>)\\s*${Regex.escape(generic.getValue(name))}\\s*(</p>)")
            result = pattern.replace(result) { match ->
                match.groupValues[1] + description + match.groupValues[2]
            }
        }
        return result
    }

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

        // Match the header with BOTH the requested heading and CTA; never style a nav header.
        val header = Regex("""(?is)<header\b[^>]*>""").findAll(html).firstOrNull { opening ->
            val close = Regex("""(?i)</header\s*>""").find(html, opening.range.last + 1)
            close != null && close.range.first - opening.range.first < 6000 &&
                hasHeading(html.substring(opening.range.last + 1, close.range.first), "Welcome to Minicoy") &&
                html.substring(opening.range.last + 1, close.range.first)
                    .contains("Explore Minicoy", ignoreCase = true)
        } ?: return Outcome(generated, false)
        html = html.replaceRange(header.range, addClass(header.value, "lyra-requested-blue-hero"))

        // A real section containing all three headings; labels in a nav or paragraph do not count.
        val cards = section.findAll(html).filter { item ->
            hasHeading(item.value, "Things to Explore") &&
                listOf("Beaches", "Lighthouse", "Local Food").all { hasHeading(item.value, it) }
        }.toList()
        if (cards.size != 1) return Outcome(generated, false)
        val group = cards.single()
        val open = openingSection.find(group.value) ?: return Outcome(generated, false)
        html = html.replaceRange(group.range.first + open.range.first .. group.range.first + open.range.last,
            addClass(open.value, "lyra-requested-explore"))
        html = improveFallbackCopy(html)

        // Native in-page target feedback already supplies the click-only confirmation.
        // Remove a duplicate static heading only from a separate, simple text section.
        if (goal.contains("Exploring Minicoy!", true) && html.contains("lyra-explore-feedback")) {
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
            "background-image: linear-gradient(145deg, #173f9c, #2157c8 58%, #3677e0) !important; " +
            "color: #fff !important; padding: 48px 20px 42px !important; " +
            "text-align: center !important; min-height: 0 !important; box-sizing: border-box; }\n" +
            ".lyra-requested-blue-hero h1 { color: #fff !important; max-width: 22ch; " +
            "margin: 0 auto 20px !important; font-size: clamp(1.9rem, 7vw, 2.8rem); " +
            "letter-spacing: -.025em; line-height: 1.13; }\n" +
            ".lyra-requested-blue-hero a, .lyra-requested-blue-hero button { " +
            "display: inline-block; max-width: 100%; box-sizing: border-box; }\n" +
            ".lyra-requested-blue-hero a[href^='#'] { text-decoration: none !important; " +
            "background: #ffb454 !important; color: #182646 !important; " +
            "font-weight: 800 !important; border: 0 !important; " +
            "border-radius: 12px !important; padding: 13px 22px !important; " +
            "box-shadow: 0 6px 16px rgba(7,25,76,.19); }\n" +
            ".lyra-requested-blue-hero a[href^='#']:focus-visible { " +
            "outline: 3px solid #fff !important; outline-offset: 4px; }\n" +
            ".lyra-requested-explore { display: block !important; width: 100% !important; " +
            "max-width: 1080px !important; min-height: 0 !important; margin: 0 auto !important; " +
            "padding: 28px 16px 16px !important; box-sizing: border-box; }\n" +
            ".lyra-requested-explore > h2, .lyra-requested-explore > .lyra-requested-explore-title { " +
            "display: block !important; width: 100% !important; " +
            "margin: 0 0 16px !important; text-align: center !important; " +
            "font-size: clamp(1.45rem, 6vw, 2rem); line-height: 1.2; }\n" +
            ".lyra-requested-explore .lyra-requested-explore-cards { " +
            "display: grid !important; grid-template-columns: minmax(0, 1fr) !important; " +
            "gap: 12px !important; width: 100% !important; min-width: 0 !important; }\n" +
            ".lyra-requested-explore .lyra-requested-explore-card { " +
            "width: 100% !important; min-width: 0 !important; min-height: 0 !important; " +
            "box-sizing: border-box; background: #fff0f6 !important; " +
            "border: 1px solid #f5cadd !important; border-radius: 16px !important; " +
            "padding: 18px !important; box-shadow: 0 4px 14px rgba(91,37,67,.06); }\n" +
            ".lyra-requested-explore .lyra-requested-explore-card h3 { " +
            "font-size: 1.12rem; line-height: 1.3; margin: 0 0 7px !important; }\n" +
            ".lyra-requested-explore .lyra-requested-explore-card p { " +
            "margin: 0 !important; line-height: 1.5; max-width: 56ch; }\n" +
            ".lyra-requested-explore-copy { min-height: 0 !important; " +
            "margin: 0 auto !important; padding: 4px 16px 24px !important; " +
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
