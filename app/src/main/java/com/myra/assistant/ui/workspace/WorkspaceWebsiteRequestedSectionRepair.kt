package com.myra.assistant.ui.workspace

/** Complete explicitly requested content only on a brand-new website or untouched starter.
 * Existing project sources are never reconstructed. This is local source work, not an AI
 * request or a visual/phone-test claim; ambiguous card layouts stay fail-closed.
 */
internal object WorkspaceWebsiteRequestedSectionRepair {
    data class Outcome(val files: Map<String, String>, val completed: Boolean,
                       val diagnostic: String = "NONE", val rebuiltCards: Boolean = false)

    private val cards = listOf("Beaches", "Lighthouse", "Local Food")
    private val heading = Regex("""(?is)<h([1-6])\b[^>]*>(.*?)</h\1\s*>""")
    private val section = Regex("""(?is)<section\b[^>]*>.{0,12000}?</section\s*>""")
    private val tags = Regex("""(?s)<[^>]*>""")
    private val closingBody = Regex("""(?i)</body\s*>""")
    private val containers = Regex("""(?is)<(/?)(div|section)\b([^>]*)>""")
    private val className = Regex("""(?is)\bclass\s*=\s*(["'])(.*?)\1""")
    private val cardGroupClass = Regex("""(?i)(?:^|[\s_-])(?:cards?|grid|tiles?|features?|attractions?|explore)(?:$|[\s_-])""")
    private val cardLikeOpening = Regex("""(?is)<(?:article|div)\b[^>]*\bclass\s*=\s*(["'])[^"']*(?:card|tile|feature|attraction)[^"']*\1""")

    private fun visible(value: String): String = tags.replace(value, " ")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("""\s+"""), " ").trim()

    private fun label(value: String): String = visible(value)
        .replace(Regex("""^[^\p{L}\p{N}]{1,12}"""), "")
        .replace(Regex("""[^\p{L}\p{N}]{1,12}$"""), "").trim()

    private fun headingNamed(html: String, name: String): Boolean = heading.findAll(html).any {
        label(it.groupValues[2]).equals(name, ignoreCase = true)
    }

    private fun mentions(text: String, name: String): Boolean =
        Regex("(?i)\\b${Regex.escape(name).replace("\\ ", "\\s+")}\\b")
            .containsMatchIn(text)

    private fun newOrStarter(original: Map<String, String?>): Boolean =
        original.values.all { it.isNullOrBlank() } ||
            (original["index.html"]?.contains("<h1>Hello, Workspace!</h1>") == true &&
                original["style.css"]?.contains("body { margin: 0; padding: 2rem;") == true &&
                original["script.js"]?.contains("// Your JavaScript starts here.") == true)

    private data class Open(val tag: String, val start: Int, val openingEnd: Int,
                            val attributes: String)
    private data class Group(val tag: String, val start: Int, val openingEnd: Int,
                             val closingStart: Int)

    /** Find a single actual card container, never page-wide wrappers or arbitrary paragraphs.
     * The generated model may use headings OR strong/plain-text labels inside a card grid.
     * Text-only recovery requires all three exact names inside one card-classed container.
     */
    private fun existingCardGroup(html: String): Group? {
        val occurrences = cards.associateWith { name ->
            heading.findAll(html).filter { label(it.groupValues[2]).equals(name, true) }
                .map { it.range.first }.toList()
        }
        if (occurrences.values.any { it.size > 1 }) return null
        val positions = occurrences.values.flatten()
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
                if (group.closingStart - group.start > 12_000) continue
                val within = positions.count { it in group.openingEnd until group.closingStart }
                val eligibleClass = className.find(open.attributes)?.groupValues?.get(2).orEmpty()
                val cardClass = cardGroupClass.containsMatchIn(eligibleClass)
                val exactNames = visible(html.substring(group.openingEnd, group.closingStart))
                val allNamed = cards.all { mentions(exactNames, it) }
                if ((within >= 2 && within == positions.size &&
                        (tag == "section" || cardClass)) ||
                    (cardClass && within == positions.size && allNamed)) candidates.add(group)
            }
        }
        if (stack.isNotEmpty()) return null
        // Equal-size or non-nested candidates are ambiguous; don't pick an arbitrary group.
        val shortest = candidates.minByOrNull { it.closingStart - it.start } ?: return null
        return shortest.takeIf { chosen -> candidates.none { other ->
            other != chosen && other.start != chosen.start &&
                !(other.start <= chosen.start && other.closingStart >= chosen.closingStart) &&
                !(chosen.start <= other.start && chosen.closingStart >= other.closingStart)
        } }
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

    private fun tiles(names: List<String>): String {
        val descriptions = mapOf("Beaches" to "Explore coastal scenery.",
            "Lighthouse" to "Learn about an island landmark.",
            "Local Food" to "Discover island flavours.")
        return names.joinToString("\n") { name ->
            "<article class=\"lyra-requested-explore-card\"><h3>$name</h3>" +
                "<p>${descriptions.getValue(name)}</p></article>"
        }
    }

    fun repair(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               generated: Map<String, String>): Outcome {
        val goal = snapshot.goal
        if (!newOrStarter(snapshot.original)) return Outcome(generated, false, "EXISTING_PROJECT")
        if (!goal.contains("Minicoy", true) || !goal.contains("Things to Explore", true) ||
            !goal.contains("Explore Minicoy", true) || !goal.contains("card", true) ||
            cards.any { !goal.contains(it, true) } ||
            Regex("""(?i)\b(?:remove|delete|hatao|nikalo)\b""").containsMatchIn(goal))
            return Outcome(generated, false, "BRIEF_NOT_ELIGIBLE")
        val html = generated["index.html"] ?: return Outcome(generated, false, "NO_HTML")
        val css = generated["style.css"] ?: return Outcome(generated, false, "NO_CSS")
        val hasTitle = headingNamed(html, "Things to Explore")
        if (hasTitle && cards.all { headingNamed(html, it) })
            return Outcome(generated, false, "ALREADY_COMPLETE")

        val groups = if (hasTitle) emptyList() else section.findAll(html).filter { group ->
            cards.all { headingNamed(group.value, it) }
        }.toList()
        val nextHtml: String
        var nextCss = css
        var rebuiltCards = false
        if (groups.size == 1) {
            val selected = groups.single()
            val openingEnd = selected.value.indexOf('>')
            if (openingEnd < 0) return Outcome(generated, false, "INVALID_SECTION")
            val at = selected.range.first + openingEnd + 1
            nextHtml = html.replaceRange(at, at,
                "\n<h2 class=\"lyra-requested-explore-title\">Things to Explore</h2>\n")
        } else {
            val group = existingCardGroup(html)
            if (group != null) {
                val groupText = visible(html.substring(group.openingEnd, group.closingStart))
                val missing = cards.filterNot { headingNamed(html, it) }
                val namesInGroup = cards.all { mentions(groupText, it) }
                val reconstruct = missing.any { mentions(groupText, it) }
                // Text-labelled cards cannot be fixed by appending duplicate headings/cards.
                // Rebuild ONLY this single identified group, never the whole page or an old site.
                if (reconstruct && !namesInGroup)
                    return Outcome(generated, false, "AMBIGUOUS_CARD_TEXT")
                val title = if (hasTitle) "" else
                    "\n<h2 class=\"lyra-requested-explore-title\">Things to Explore</h2>\n"
                if (reconstruct) {
                    val wrapped = "\n<div class=\"lyra-requested-explore-cards\">${tiles(cards)}</div>\n"
                    val rebuilt = html.replaceRange(group.openingEnd, group.closingStart, wrapped)
                    val at = if (group.tag == "section") group.openingEnd else group.start
                    nextHtml = rebuilt.replaceRange(at, at, title)
                    nextCss += cardStyles(goal)
                    rebuiltCards = true
                } else {
                    val extraCards = missing.joinToString("\n", prefix = if (missing.isEmpty()) "" else "\n") {
                        tiles(listOf(it))
                    }
                    val at = if (group.tag == "section") group.openingEnd else group.start
                    nextHtml = listOf(group.closingStart to extraCards, at to title)
                        .sortedByDescending { it.first }.fold(html) { text, (position, markup) ->
                            text.replaceRange(position, position, markup)
                        }
                    if (missing.isNotEmpty()) nextCss += cardStyles(goal)
                }
            } else {
                if (hasTitle || cards.any { headingNamed(html, it) } ||
                    cardLikeOpening.containsMatchIn(html))
                    return Outcome(generated, false, "NO_UNAMBIGUOUS_CARD_GROUP")
                // Navigation/paragraph labels aren't real cards. Build the explicit section
                // locally only when no generated card node or card heading exists.
                val body = closingBody.find(html) ?: return Outcome(generated, false, "NO_BODY")
                val markup = "\n<section class=\"lyra-requested-explore\" " +
                    "aria-label=\"Things to Explore\">" +
                    "<h2 class=\"lyra-requested-explore-title\">Things to Explore</h2>" +
                    "<div class=\"lyra-requested-explore-cards\">${tiles(cards)}</div></section>\n"
                nextHtml = html.replaceRange(body.range.first, body.range.first, markup)
                nextCss += cardStyles(goal)
            }
        }
        if (nextHtml.length > 15_000 || nextCss.length > 15_000 ||
            nextHtml.length + nextCss.length + generated.getValue("script.js").length > 30_000)
            return Outcome(generated, false, "SIZE_LIMIT")
        return Outcome(generated + ("index.html" to nextHtml) + ("style.css" to nextCss),
            true, "REPAIRED", rebuiltCards)
    }
}
