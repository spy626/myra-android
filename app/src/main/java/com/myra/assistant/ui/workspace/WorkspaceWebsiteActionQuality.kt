package com.myra.assistant.ui.workspace

/** Narrow, deterministic navigation repair for the explicitly requested Explore Minicoy CTA.
 * A visual layout check alone cannot show whether a generated control has an action.
 * Native in-page links work without a JavaScript listener, remote asset or Android bridge.
 * No other button, destination or user-specified alternate action is silently changed.
 */
internal object WorkspaceWebsiteActionQuality {
    data class Review(val files: Map<String, String>, val repaired: Boolean) {
        fun chatNote(): String = if (repaired)
            "\nExplore Minicoy now links to Things to Explore with visible target feedback. " +
                "Tap it in Preview to confirm on your phone."
        else ""
    }

    private val heading = Regex("""(?is)<h([1-6])\b([^>]*)>(.*?)</h\1\s*>""")
    private val control = Regex("""(?is)<(button|a)\b([^>]*)>(.*?)</\1\s*>""")
    private val id = Regex("""(?is)\bid\s*=\s*(["'])([a-zA-Z][\w:.-]{0,63})\1""")
    private val href = Regex("""(?is)\bhref\s*=\s*(["'])(#[a-zA-Z][\w:.-]{0,63})\1""")
    private val classes = Regex("""(?is)\bclass\s*=\s*(["'])(.*?)\1""")
    private val style = Regex("""(?is)\bstyle\s*=\s*(["'])(.*?)\1""")
    private val tags = Regex("""(?s)<[^>]*>""")
    private val spaces = Regex("""\s+""")
    private const val TARGET_CLASS = "lyra-explore-target"
    private const val FEEDBACK = "\n/* LYRA Explore target feedback */\n" +
        ".lyra-explore-target:target { outline: 2px solid #0f766e; " +
        "outline-offset: 6px; scroll-margin-top: 24px; border-radius: 6px; }\n"

    private fun text(html: String): String = spaces.replace(
        tags.replace(html, "").replace("&nbsp;", " ").trim(), " ")

    private fun hasDifferentRequestedAction(goal: String): Boolean =
        // Explicitly requesting an alternate action (e.g. show Welcome on click)
        // is authoritative. Do not swap that action for default section navigation.
        Regex("""(?is)(?:explore\s+minicoy|explore\s+button).{0,100}\b(?:click|tap|press|dabao|dabane)\b.{0,100}\bwelcome\s+to\s+minicoy\b""")
            .containsMatchIn(goal) ||
        Regex("""(?is)\b(?:click|tap|press)\b.{0,100}\b(?:alert|modal|new\s+page|external\s+link)\b""")
            .containsMatchIn(goal)

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot, files: Map<String, String>): Review {
        val goal = snapshot.goal
        if (!Regex("""(?i)\bexplore\s+minicoy\b""").containsMatchIn(goal) ||
            hasDifferentRequestedAction(goal)) return Review(files, false)
        val html = files["index.html"] ?: return Review(files, false)
        val css = files["style.css"] ?: return Review(files, false)
        val section = heading.findAll(html).firstOrNull {
            text(it.groupValues[3]).equals("Things to Explore", ignoreCase = true)
        } ?: return Review(files, false)
        val cta = control.findAll(html).firstOrNull {
            text(it.groupValues[3]).equals("Explore Minicoy", ignoreCase = true)
        } ?: return Review(files, false)

        val headingId = id.find(section.groupValues[2])?.groupValues?.get(2)
        // A stable, unique internal anchor: do not steal any pre-existing element ID.
        val target = headingId ?: (0..8).map { if (it == 0) "lyra-explore-section" else "lyra-explore-section-$it" }
            .firstOrNull { candidate ->
                !Regex("""(?is)\bid\s*=\s*(["'])${Regex.escape(candidate)}\1""")
                    .containsMatchIn(html)
            } ?: return Review(files, false)
        val existingHref = href.find(cta.groupValues[2])?.groupValues?.get(2)
        val alreadyLinked = cta.groupValues[1].equals("a", ignoreCase = true) &&
            existingHref == "#$target"

        var fixedHtml = html
        if (!alreadyLinked) {
            // Preserve visual classes/styles, not onclick, disabled or stale JS binding IDs.
            // Avoid binding script.js to the replacement link through the previous button ID.
            val attributes = cta.groupValues[2]
            val visualAttrs = listOfNotNull(classes.find(attributes)?.value,
                style.find(attributes)?.value).joinToString(" ")
            val extra = if (visualAttrs.isBlank()) "" else " $visualAttrs"
            val replacement = "<a href=\"#$target\"$extra>${cta.groupValues[3]}</a>"
            fixedHtml = fixedHtml.replaceRange(cta.range, replacement)
        }
        // Re-find after changing the CTA because it may precede the heading.
        val freshHeading = heading.findAll(fixedHtml).firstOrNull {
            text(it.groupValues[3]).equals("Things to Explore", ignoreCase = true)
        } ?: return Review(files, false)
        var opening = freshHeading.value.substringBefore('>')
        if (headingId == null) opening += " id=\"$target\""
        val currentClass = classes.find(opening)
        opening = if (currentClass == null) "$opening class=\"$TARGET_CLASS\"" else {
            val classList = currentClass.groupValues[2]
            if (classList.split(spaces).contains(TARGET_CLASS)) opening else opening.replaceRange(
                currentClass.range,
                "class=\"${classList.trim()} $TARGET_CLASS\"")
        }
        fixedHtml = fixedHtml.replaceRange(freshHeading.range,
            opening + ">" + freshHeading.value.substringAfter('>'))
        val fixedCss = if (css.contains("/* LYRA Explore target feedback */")) css else css + FEEDBACK
        require(fixedHtml.length <= 15_000 && fixedCss.length <= 15_000) {
            "Explore link repair exceeds website file limit; no files changed"
        }
        val updated = files + ("index.html" to fixedHtml) + ("style.css" to fixedCss)
        return Review(updated, updated != files)
    }
}
