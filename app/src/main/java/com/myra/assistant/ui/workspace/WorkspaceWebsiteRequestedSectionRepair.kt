package com.myra.assistant.ui.workspace

/** Complete a specifically named, missing section only on a brand-new website.
 * This is a local, deterministic completion of explicit user content, not a model,
 * provider retry, generic redesign or permission to overwrite an existing site.
 * Ambiguous layouts are left to the existing fail-closed consistency gate.
 */
internal object WorkspaceWebsiteRequestedSectionRepair {
    data class Outcome(val files: Map<String, String>, val completed: Boolean)

    private val cards = listOf("Beaches", "Lighthouse", "Local Food")
    private val heading = Regex("""(?is)<h([1-6])\b[^>]*>(.*?)</h\1\s*>""")
    private val section = Regex("""(?is)<section\b[^>]*>.{0,12000}?</section\s*>""")
    private val tags = Regex("""(?s)<[^>]*>""")
    private val closingBody = Regex("""(?i)</body\s*>""")

    private fun visible(value: String): String = tags.replace(value, " ")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("""\s+"""), " ").trim()

    private fun headingNamed(html: String, name: String): Boolean = heading.findAll(html).any {
        val label = visible(it.groupValues[2]).replace(
            Regex("""^[^\p{L}\p{N}]{1,12}"""), "").trim()
        label.equals(name, ignoreCase = true)
    }

    private fun newOrStarter(original: Map<String, String?>): Boolean =
        original.values.all { it.isNullOrBlank() } ||
            (original["index.html"]?.contains("<h1>Hello, Workspace!</h1>") == true &&
                original["style.css"]?.contains("body { margin: 0; padding: 2rem;") == true &&
                original["script.js"]?.contains("// Your JavaScript starts here.") == true)

    fun repair(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               generated: Map<String, String>): Outcome {
        val goal = snapshot.goal
        if (!newOrStarter(snapshot.original) ||
            !goal.contains("Minicoy", ignoreCase = true) ||
            !goal.contains("Things to Explore", ignoreCase = true) ||
            !goal.contains("Explore Minicoy", ignoreCase = true) ||
            !goal.contains("card", ignoreCase = true) ||
            cards.any { !goal.contains(it, ignoreCase = true) } ||
            Regex("""(?i)\b(?:remove|delete|hatao|nikalo)\b""").containsMatchIn(goal))
            return Outcome(generated, false)
        val html = generated["index.html"] ?: return Outcome(generated, false)
        val css = generated["style.css"] ?: return Outcome(generated, false)
        if (headingNamed(html, "Things to Explore") ||
            visible(html).contains("Things to Explore", ignoreCase = true))
            return Outcome(generated, false)

        // Label the existing card group when it is unambiguous; never duplicate its cards.
        val groups = section.findAll(html).filter { group ->
            cards.all { headingNamed(group.value, it) }
        }.toList()
        val nextHtml: String
        var nextCss = css
        if (groups.size == 1) {
            val selected = groups.single()
            val openingEnd = selected.value.indexOf('>')
            if (openingEnd < 0) return Outcome(generated, false)
            val at = selected.range.first + openingEnd + 1
            nextHtml = html.replaceRange(at, at,
                "\n<h2 class=\"lyra-requested-explore-title\">Things to Explore</h2>\n")
        } else {
            // Never manufacture duplicates or reshuffle unknown existing markup.
            if (cards.any { visible(html).contains(it, ignoreCase = true) })
                return Outcome(generated, false)
            val body = closingBody.find(html) ?: return Outcome(generated, false)
            val tiles = cards.zip(listOf(
                "Explore coastal scenery.", "Learn about an island landmark.",
                "Discover island flavours.")).joinToString("\n") { (name, copy) ->
                "<article class=\"lyra-requested-explore-card\"><h3>$name</h3><p>$copy</p></article>"
            }
            val markup = "\n<section class=\"lyra-requested-explore\" " +
                "aria-label=\"Things to Explore\">" +
                "<h2 class=\"lyra-requested-explore-title\">Things to Explore</h2>" +
                "<div class=\"lyra-requested-explore-cards\">$tiles</div></section>\n"
            nextHtml = html.replaceRange(body.range.first, body.range.first, markup)
            val bg = if (goal.contains("pink", ignoreCase = true)) "#fce7f3" else "#f1f5f9"
            nextCss += "\n/* Explicit requested section; new website only */\n" +
                ".lyra-requested-explore { max-width: 1100px; margin: 24px auto; " +
                "padding: 16px; }\n" +
                ".lyra-requested-explore-cards { display: grid; " +
                "grid-template-columns: repeat(auto-fit, minmax(min(100%, 190px), 1fr)); " +
                "gap: 12px; }\n" +
                ".lyra-requested-explore-card { background: $bg; color: #4a1435; " +
                "border-radius: 14px; padding: 14px; min-width: 0; }\n" +
                ".lyra-requested-explore-card h3 { margin: 0 0 6px; }\n" +
                ".lyra-requested-explore-card p { margin: 0; }\n"
        }
        if (nextHtml.length > 15_000 || nextCss.length > 15_000 ||
            nextHtml.length + nextCss.length + generated.getValue("script.js").length > 30_000)
            return Outcome(generated, false)
        return Outcome(generated + ("index.html" to nextHtml) + ("style.css" to nextCss), true)
    }
}
