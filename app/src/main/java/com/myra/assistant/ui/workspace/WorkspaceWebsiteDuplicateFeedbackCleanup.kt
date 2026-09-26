package com.myra.assistant.ui.workspace

/** Removes only the observed duplicate Explore status from an untouched, new Minicoy site.
 * The native link/CSS :target feedback must already be complete. Unknown code, other
 * interactions and established projects remain untouched; this is not a CSS/HTML parser.
 */
internal object WorkspaceWebsiteDuplicateFeedbackCleanup {
    private val nativeLink = Regex(
        """(?is)<a\b[^>]*\bhref\s*=\s*(['"])#([a-zA-Z][\w:.-]{0,63})\1[^>]*>\s*Explore\s+Minicoy\s*</a\s*>""")
    private val status = Regex(
        """(?is)<p\b[^>]*\bclass\s*=\s*(['"])lyra-explore-feedback\1[^>]*>\s*Exploring\s+Minicoy!\s*</p\s*>""")
    private val redundant = Regex(
        """(?is)<p\s+class\s*=\s*(['"])feedback\1\s*>\s*Exploring\s+Minicoy!\s*</p\s*>""")
    private val classes = Regex("""(?is)\bclass\s*=\s*(['"])(.*?)\1""")
    private val oldClassSelector = Regex("""(?i)(?<![\w-])\.feedback(?![\w-])""")
    // Accept only these complete single-selector rules, not grouped/other scoped selectors.
    private val hiddenRule = Regex(
        """(?m)^[ \t]*(?:\.feedback|\.explore[ \t]+\.feedback)[ \t]*\{[^{}]{0,700}\}[ \t]*(?:\r?\n)?""")
    private val obsoleteTargetRule = Regex(
        """(?m)^[ \t]*(?:/\* Show feedback when section is target \*/[ \t]*\r?\n)?[ \t]*#explore-section:target[ \t]+\.feedback[ \t]*\{[^{}]{0,700}\}[ \t]*(?:\r?\n)?""")
    private val hidden = Regex("""(?i)\bdisplay\s*:\s*none\b""")
    private val shown = Regex("""(?i)\bdisplay\s*:\s*block\b""")
    // Inspect actual CSS declarations, not the word 'opacity' in 'transition: opacity'.
    // Repeated declarations and mixed display/opacity modes are not safe to infer.
    private val opacityProperty = Regex("""(?i)[;{]\s*opacity\s*:\s*([^;{}]{1,60}?)(?=[;}])""")
    private val displayProperty = Regex("""(?i)[;{]\s*display\s*:""")
    private val visibilityProperty = Regex("""(?i)[;{]\s*visibility\s*:""")
    private val zeroOpacity = Regex("""(?i)0(?:\.0+)?(?:\s*!important)?""")
    private val fullOpacity = Regex("""(?i)1(?:\.0+)?(?:\s*!important)?""")
    private fun opacityIs(rule: String, expected: Regex): Boolean {
        val declarations = opacityProperty.findAll(rule).map { it.groupValues[1].trim() }.toList()
        return declarations.size == 1 && expected.matches(declarations.single())
    }
    private val exploreSectionId = Regex("""(?is)\bid\s*=\s*(['"])explore-section\1""")
    private val openingSection = Regex("""(?is)<section\b([^>]{0,500})>""")
    private val closingSection = Regex("""(?i)</section\s*>""")

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               files: Map<String, String>): Map<String, String> {
        if (snapshot.original.values.any { it != null }) return files
        if (!listOf("Minicoy", "Explore Minicoy", "Things to Explore", "Exploring Minicoy!")
                .all { snapshot.goal.contains(it, ignoreCase = true) }) return files
        val html = files["index.html"] ?: return files
        val css = files["style.css"] ?: return files
        val script = files["script.js"] ?: return files
        // Never remove a node or a rule that an unknown generated script may consume.
        if (script.isNotBlank() && script != WorkspaceWebsiteNativeActionOwner.REPLACEMENT) return files
        val link = nativeLink.findAll(html).singleOrNull() ?: return files
        val target = link.groupValues[2]
        if (target == "explore-section") return files
        val heading = Regex(
            """(?is)<h[1-6]\b[^>]*\bid\s*=\s*(['"])${Regex.escape(target)}\1[^>]*>\s*Things\s+to\s+Explore\s*</h[1-6]\s*>""")
        if (!heading.containsMatchIn(html) ||
            !css.contains(".lyra-explore-target:target + .lyra-explore-feedback")) return files
        val primary = status.findAll(html).singleOrNull() ?: return files
        val extra = redundant.findAll(html).singleOrNull() ?: return files
        if (extra.range.first <= primary.range.last ||
            html.substring(primary.range.last + 1, extra.range.first).isNotBlank()) return files
        // No other HTML consumer of the old class, including multi-class attributes.
        val oldClassUses = classes.findAll(html).count { match ->
            match.groupValues[2].split(Regex("""\s+""")).contains("feedback")
        }
        if (oldClassUses != 1 || oldClassSelector.findAll(css).count() != 2) return files
        val oldHidden = hiddenRule.findAll(css).singleOrNull() ?: return files
        val oldTarget = obsoleteTargetRule.findAll(css).singleOrNull() ?: return files
        if (oldHidden.range.first == oldTarget.range.first) return files
        val displayPair = hidden.containsMatchIn(oldHidden.value) &&
            shown.containsMatchIn(oldTarget.value) &&
            !opacityProperty.containsMatchIn(oldHidden.value) &&
            !opacityProperty.containsMatchIn(oldTarget.value)
        val opacityPair = opacityIs(oldHidden.value, zeroOpacity) &&
            opacityIs(oldTarget.value, fullOpacity) &&
            listOf(oldHidden.value, oldTarget.value).none {
                displayProperty.containsMatchIn(it) || visibilityProperty.containsMatchIn(it)
            }
        if (!displayPair && !opacityPair) return files
        // A `.explore .feedback` rule may belong to another component. Only clean it
        // when one actual <section id="explore-section" class="explore"> contains BOTH
        // statuses, as observed on the phone. Don't silently remove unrelated CSS.
        if (oldHidden.value.trimStart().startsWith(".explore")) {
            val sections = openingSection.findAll(html).filter { opening ->
                val attrs = opening.groupValues[1]
                exploreSectionId.containsMatchIn(attrs) &&
                    classes.find(attrs)?.groupValues?.get(2)
                        ?.split(Regex("""\s+"""))?.contains("explore") == true
            }.toList()
            val section = sections.singleOrNull() ?: return files
            val close = closingSection.find(html, section.range.last + 1) ?: return files
            if (primary.range.first <= section.range.last ||
                extra.range.last >= close.range.first ||
                openingSection.find(html, section.range.last + 1)
                    ?.range?.first?.let { it < close.range.first } == true) return files
        }
        val cleanedCss = listOf(oldHidden.range, oldTarget.range)
            .sortedByDescending { it.first }
            .fold(css) { source, range -> source.removeRange(range) }
        val cleanedHtml = html.removeRange(extra.range)
        return files + ("index.html" to cleanedHtml) + ("style.css" to cleanedCss)
    }
}
