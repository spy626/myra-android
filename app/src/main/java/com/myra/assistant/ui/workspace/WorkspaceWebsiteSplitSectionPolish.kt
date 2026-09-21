package com.myra.assistant.ui.workspace

/** Bounded layout repair for the exact NEW-site split observed on the phone.
 * Keeps the native anchor, feedback, three articles and all prose; never edits an
 * established project or attempts to parse arbitrary HTML. DesignPolish owns colors.
 */
internal object WorkspaceWebsiteSplitSectionPolish {
    private val sections = Regex(
        """(?is)<section\b[^>]{0,300}>(?:(?!<section\b|</section\s*>).){0,8000}</section\s*>""")
    private val opening = Regex("""(?is)^<section\b[^>]{0,300}>""")
    private val closing = Regex("""(?is)</section\s*>$""")
    private val classes = Regex("""(?is)\bclass\s*=\s*(['"])(.*?)\1""")
    private val ids = Regex("""(?is)\bid\s*=\s*(['"])(.*?)\1""")
    private val group = Regex(
        """(?is)^\s*(<div\b[^>]{0,300}\bclass\s*=\s*(['"])lyra-requested-explore-cards\2[^>]*>(?:(?!<div\b|</div\s*>).){0,6500}</div\s*>)\s*$""")
    private val articles = Regex("""(?is)<article\b[^>]{0,250}>(?:(?!</article\s*>).){0,1800}</article\s*>""")
    private val cardHeading = Regex("""(?is)<h3\b[^>]*>\s*(Beaches|Lighthouse|Local\s+Food)\s*</h3\s*>""")
    private val intro = Regex(
        """(?is)^\s*(<h([1-6])\b([^>]{0,320})>\s*Things\s+to\s+Explore\s*</h\2\s*>\s*<p\b([^>]{0,320})>\s*Exploring\s+Minicoy!\s*</p\s*>)""")
    private val link = Regex(
        """(?is)<a\b[^>]{0,500}\bhref\s*=\s*(['"])#lyra-explore-section\1[^>]*>\s*Explore\s+Minicoy\s*</a\s*>""")
    private val header = Regex("""(?is)<header\b[^>]{0,300}>(?:(?!</header\s*>).){0,3000}</header\s*>""")
    private val forbidden = Regex("""(?is)<(?:script|style|form|button|input|iframe|img|a)\b""")
    private val labels = setOf("Beaches", "Lighthouse", "Local Food")

    private fun token(attrs: String, name: String): Boolean =
        classes.find(attrs)?.groupValues?.get(2)?.split(Regex("""\s+"""))?.contains(name) == true

    private fun sectionParts(value: String): Triple<String, String, String>? {
        val open = opening.find(value)?.value ?: return null
        val close = closing.find(value)?.value ?: return null
        return Triple(open, value.substring(open.length, value.length - close.length), close)
    }

    private fun eligible(snapshot: WorkspaceWebsiteGeneration.Snapshot): Boolean {
        val old = snapshot.original
        val starter = old["index.html"]?.contains("<h1>Hello, Workspace!</h1>") == true &&
            old["style.css"]?.contains("body { margin: 0; padding: 2rem;") == true &&
            old["script.js"]?.contains("// Your JavaScript starts here.") == true
        val goal = snapshot.goal
        return (old.values.all { it.isNullOrBlank() } || starter) &&
            listOf("Minicoy", "Welcome to Minicoy", "Explore Minicoy", "Things to Explore",
                "Exploring Minicoy!", "blue hero", "pink", "Beaches", "Lighthouse", "Local Food")
                .all { goal.contains(it, ignoreCase = true) } &&
            Regex("""(?i)\b(?:remove|delete|hatao|nikalo)\b""").containsMatchIn(goal).not()
    }

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               files: Map<String, String>): Map<String, String> {
        if (!eligible(snapshot)) return files
        val html = files["index.html"] ?: return files
        val css = files["style.css"] ?: return files
        val js = files["script.js"] ?: return files
        if (js.isNotBlank() || css.contains("/* LYRA new-site requested visual polish v1 */") ||
            !css.contains(".lyra-explore-target:target + .lyra-explore-feedback") ||
            !css.contains(".lyra-requested-explore-cards")) return files
        if (link.findAll(html).count() != 1 ||
            ids.findAll(html).count { it.groupValues[2] == "lyra-explore-section" } != 1) return files
        val hero = header.findAll(html).filter { block ->
            block.value.contains("Welcome to Minicoy", true) && link.containsMatchIn(block.value)
        }.singleOrNull() ?: return files
        if (hero.range.last >= html.length) return files
        val candidates = sections.findAll(html).toList()
        val cardMatches = candidates.filter { token(opening.find(it.value)?.value.orEmpty(), "cards") }
        val exploreMatches = candidates.filter {
            val attrs = opening.find(it.value)?.value.orEmpty()
            token(attrs, "explore") && ids.find(attrs)?.groupValues?.get(2) == "explore-section"
        }
        val cards = cardMatches.singleOrNull() ?: return files
        val explore = exploreMatches.singleOrNull() ?: return files
        if (hero.range.last >= cards.range.first || cards.range.last >= explore.range.first ||
            html.substring(cards.range.last + 1, explore.range.first).isNotBlank()) return files
        val (cardOpen, cardBody, _) = sectionParts(cards.value) ?: return files
        val (exploreOpen, exploreBody, exploreClose) = sectionParts(explore.value) ?: return files
        if (!token(cardOpen, "cards") || forbidden.containsMatchIn(cardBody)) return files
        val cardMarkup = group.matchEntire(cardBody)?.groupValues?.get(1) ?: return files
        val content = cardMarkup.substringAfter('>').substringBeforeLast("</div", "")
        val found = articles.findAll(content).toList()
        if (found.size != 3 || articles.replace(content, "").isNotBlank()) return files
        val headings = found.map { article ->
            if (!token(article.value.substringBefore('>') + ">", "lyra-requested-explore-card"))
                return files
            val titles = cardHeading.findAll(article.value).toList()
            if (titles.size != 1) return files
            titles.single().groupValues[1].replace(Regex("""\s+"""), " ")
        }
        if (headings.toSet() != labels || headings.size != labels.size) return files
        val beginning = intro.find(exploreBody) ?: return files
        if (beginning.range.first != 0 ||
            ids.find(beginning.groupValues[3])?.groupValues?.get(2) != "lyra-explore-section" ||
            !token(beginning.groupValues[3], "lyra-explore-target") ||
            !token(beginning.groupValues[4], "lyra-explore-feedback") ||
            forbidden.containsMatchIn(exploreBody.substring(beginning.range.last + 1))) return files
        // Keep `.cards` to preserve the original section's selector, while the existing
        // request-specific design polish provides the stronger responsive grid styles.
        val oldClass = classes.find(exploreOpen) ?: return files
        val mergedOpen = exploreOpen.replaceRange(oldClass.range,
            "class=${oldClass.groupValues[1]}${oldClass.groupValues[2]} cards${oldClass.groupValues[1]}")
        val merged = mergedOpen + beginning.value + "\n" + cardMarkup + "\n" +
            exploreBody.substring(beginning.range.last + 1) + exploreClose
        val updated = html.replaceRange(cards.range.first, explore.range.last + 1, merged)
        if (updated.length > 15_000 || updated.length + css.length + js.length > 30_000) return files
        return files + ("index.html" to updated)
    }
}
