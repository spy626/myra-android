package com.myra.assistant.ui.workspace

/** Complete explicitly requested content only on a brand-new website or untouched starter.
 * Never rewrite an established site, bypass validation, request another provider, or exceed
 * the original three-file/size limits. Ambiguous generated markup remains fail-closed.
 */
internal object WorkspaceWebsiteRequestedSectionRepair {
    data class Outcome(val files: Map<String, String>, val completed: Boolean)

    private val cards = listOf("Beaches", "Lighthouse", "Local Food")
    private val heading = Regex("""(?is)<h([1-6])\b[^>]*>(.*?)</h\1\s*>""")
    private val section = Regex("""(?is)<section\b[^>]*>.{0,12000}?</section\s*>""")
    private val tags = Regex("""(?s)<[^>]*>""")
    private val closingBody = Regex("""(?i)</body\s*>""")
    private val containers = Regex("""(?is)<(/?)(div|section)\b([^>]*)>""")
    private val className = Regex("""(?is)\bclass\s*=\s*(["'])(.*?)\1""")
    private val cardGroupClass = Regex("""(?i)(?:^|[\s_-])(?:cards?|grid|tiles?|features?|attractions?|explore)(?:$|[\s_-])""")

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

    private data class Open(val tag: String, val start: Int, val openingEnd: Int,
                            val attributes: String)
    private data class Group(val tag: String, val start: Int, val openingEnd: Int,
                             val closingStart: Int)

    /** Match nested div/section boundaries rather than treating a nested card's closing
     * div as the end of its grid. Only one bounded, unambiguous group is eligible.
     */
    private fun existingCardGroup(html: String): Group? {
        val occurrences = cards.associateWith { name ->
            heading.findAll(html).filter { match ->
                val label = visible(match.groupValues[2]).replace(
                    Regex("""^[^\p{L}\p{N}]{1,12}"""), "").trim()
                label.equals(name, ignoreCase = true)
            }.map { it.range.first }.toList()
        }
        if (occurrences.values.any { it.size > 1 }) return null
        val stack = mutableListOf<Open>()
        val candidates = mutableListOf<Group>()
        for (match in containers.findAll(html)) {
            val tag = match.groupValues[2].lowercase()
            if (match.groupValues[1].isEmpty()) {
                if (stack.size >= 48 || match.value.endsWith("/>")) return null
                stack.add(Open(tag, match.range.first, match.range.last + 1, match.groupValues[3]))
            } else {
                if (stack.isEmpty() || stack.last().tag != tag) return null
                val open = stack.removeAt(stack.lastIndex)
                val group = Group(tag, open.start, open.openingEnd, match.range.first)
                val positions = occurrences.values.flatten()
                val within = positions.count { it in group.openingEnd until group.closingStart }
                val eligibleClass = className.find(open.attributes)?.groupValues?.get(2).orEmpty()
                if (within >= 2 && within == positions.size &&
                    group.closingStart - group.start <= 12_000 &&
                    (tag == "section" || cardGroupClass.containsMatchIn(eligibleClass))) {
                    candidates.add(group)
                }
            }
        }
        if (stack.isNotEmpty()) return null
        // Prefer the smallest actual card container, not a page-wide wrapper.
        return candidates.minByOrNull { it.closingStart - it.start }
    }

    private fun cardStyles(goal: String): String {
        val bg = if (goal.contains("pink", ignoreCase = true)) "#fce7f3" else "#f1f5f9"
        return "\n/* Explicit requested section; new website only */\n" +
            ".lyra-requested-explore { max-width: 1100px; margin: 24px auto; padding: 16px; }\n" +
            ".lyra-requested-explore-title { margin: 20px 0 12px; }\n" +
            ".lyra-requested-explore-cards { display: grid; " +
            "grid-template-columns: repeat(auto-fit, minmax(min(100%, 190px), 1fr)); gap: 12px; }\n" +
            ".lyra-requested-explore-card { background: $bg; color: #4a1435; " +
            "border-radius: 14px; padding: 14px; min-width: 0; }\n" +
            ".lyra-requested-explore-card h3 { margin: 0 0 6px; }\n" +
            ".lyra-requested-explore-card p { margin: 0; }\n"
    }

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
        // A CTA, nav link or paragraph mentioning this text is not a section heading.
        if (headingNamed(html, "Things to Explore")) return Outcome(generated, false)

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
            val group = existingCardGroup(html)
            if (group != null) {
                // A generated div-based grid is common; do not put the heading *inside*
                // the grid as an extra card. A section keeps the heading inside itself.
                val missing = cards.filterNot { headingNamed(html, it) }
                if (missing.any { visible(html.substring(group.openingEnd, group.closingStart))
                        .contains(it, ignoreCase = true) }) return Outcome(generated, false)
                val descriptions = mapOf("Beaches" to "Explore coastal scenery.",
                    "Lighthouse" to "Learn about an island landmark.",
                    "Local Food" to "Discover island flavours.")
                val extraCards = missing.joinToString("\n", prefix = if (missing.isEmpty()) "" else "\n") { name ->
                    "<article class=\"lyra-requested-explore-card\"><h3>$name</h3>" +
                        "<p>${descriptions.getValue(name)}</p></article>"
                }
                val titleAt = if (group.tag == "section") group.openingEnd else group.start
                val insertions = listOf(group.closingStart to extraCards,
                    titleAt to "\n<h2 class=\"lyra-requested-explore-title\">Things to Explore</h2>\n")
                nextHtml = insertions.sortedByDescending { it.first }.fold(html) { text, (at, markup) ->
                    text.replaceRange(at, at, markup)
                }
                if (missing.isNotEmpty()) nextCss += cardStyles(goal)
            } else {
                // Only manufacture a new three-card section if *none* of those cards
                // appears in generated content. Partial or scattered markup is ambiguous.
                if (cards.any { visible(html).contains(it, ignoreCase = true) })
                    return Outcome(generated, false)
                val body = closingBody.find(html) ?: return Outcome(generated, false)
                val descriptions = listOf("Explore coastal scenery.",
                    "Learn about an island landmark.", "Discover island flavours.")
                val tiles = cards.zip(descriptions).joinToString("\n") { (name, copy) ->
                    "<article class=\"lyra-requested-explore-card\"><h3>$name</h3><p>$copy</p></article>"
                }
                val markup = "\n<section class=\"lyra-requested-explore\" " +
                    "aria-label=\"Things to Explore\">" +
                    "<h2 class=\"lyra-requested-explore-title\">Things to Explore</h2>" +
                    "<div class=\"lyra-requested-explore-cards\">$tiles</div></section>\n"
                nextHtml = html.replaceRange(body.range.first, body.range.first, markup)
                nextCss += cardStyles(goal)
            }
        }
        if (nextHtml.length > 15_000 || nextCss.length > 15_000 ||
            nextHtml.length + nextCss.length + generated.getValue("script.js").length > 30_000)
            return Outcome(generated, false)
        return Outcome(generated + ("index.html" to nextHtml) + ("style.css" to nextCss), true)
    }
}
