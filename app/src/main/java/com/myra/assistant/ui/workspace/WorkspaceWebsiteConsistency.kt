package com.myra.assistant.ui.workspace

/** Provider-independent, source-only acceptance checks for explicitly requested website content.
 * This is a minimum functional contract, not proof that pixels look identical or a button was
 * physically tapped. It never edits files, calls a model, or reads anything outside the snapshot.
 */
internal object WorkspaceWebsiteConsistency {
    private val heading = Regex("""(?is)<h([1-6])\b([^>]*)>(.*?)</h\1\s*>""")
    private val control = Regex("""(?is)<(a|button)\b([^>]*)>(.*?)</\1\s*>""")
    private val tags = Regex("""(?s)<[^>]*>""")
    private val whitespace = Regex("""\s+""")
    private val href = Regex("""(?is)\bhref\s*=\s*(["'])#([a-zA-Z][\w:.-]{0,63})\1""")
    private val id = Regex("""(?is)\bid\s*=\s*(["'])([a-zA-Z][\w:.-]{0,63})\1""")
    private val alternateExplore = Regex(
        """(?is)(?:explore\s+minicoy|explore\s+button).{0,100}\b(?:click|tap|press|dabao|dabane)\b.{0,100}\bwelcome\s+to\s+minicoy\b|\b(?:click|tap|press)\b.{0,100}\b(?:alert|modal|new\s+page|external\s+link)\b""")

    private fun visible(html: String): String = whitespace.replace(
        tags.replace(html, " ").replace("&nbsp;", " ").replace("&amp;", "&").trim(), " ")

    private fun named(label: String, name: String): Boolean = label.trim()
        .replace(Regex("""^[^\p{L}\p{N}]{1,12}"""), "")
        .replace(Regex("""[^\p{L}\p{N}]{1,12}$"""), "")
        .trim().equals(name, true)

    private fun explicitlyRemove(goal: String, name: String): Boolean {
        val verb = "(?:remove|delete|drop|hatao|hata|nikalo)"
        val namePattern = Regex.escape(name).replace("\\ ", "\\s+")
        return Regex("(?is)\\b$verb\\b(?:\\s+\\w+){0,3}\\s+$namePattern\\b|" +
            "$namePattern(?:\\s+\\w+){0,3}\\s+\\b$verb\\b").containsMatchIn(goal)
    }

    /** Refuse incomplete three-file results before WorkspaceWebsiteGeneration.apply/Undo.
     * Require only titles explicitly named by the user or already in their project.
     * Unrelated website requests must never acquire Minicoy-specific requirements.
     */
    fun verify(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               files: Map<String, String>): Map<String, String> {
        require(files.keys == WorkspaceWebsiteGeneration.PATHS.toSet()) {
            "Incomplete website files; original project unchanged"
        }
        val html = files.getValue("index.html")
        val css = files.getValue("style.css")
        val goal = snapshot.goal
        val headings = heading.findAll(html).map { visible(it.groupValues[3]) }.toList()
        val old = snapshot.original["index.html"].orEmpty()
        val oldHeadings = heading.findAll(old).map { visible(it.groupValues[3]) }.toList()
        fun has(name: String, choices: List<String>) = choices.any { named(it, name) }
        fun required(name: String) = !explicitlyRemove(goal, name) &&
            (goal.contains(name, true) || has(name, oldHeadings))
        val mustWelcome = required("Welcome to Minicoy")
        val mustExplore = required("Things to Explore")
        if (mustWelcome) require(has("Welcome to Minicoy", headings)) {
            "Website omitted the requested Welcome to Minicoy heading; no files changed"
        }
        if (mustExplore) require(has("Things to Explore", headings)) {
            "Website omitted the requested Things to Explore section; no files changed"
        }
        val cardNames = listOf("Beaches", "Lighthouse", "Local Food")
        val asksThreeCards = cardNames.all { goal.contains(it, true) } &&
            Regex("""(?i)\b(?:card|cards)\b""").containsMatchIn(goal)
        if (asksThreeCards) cardNames.forEach { card ->
            if (!explicitlyRemove(goal, card)) require(has(card, headings)) {
                "Website omitted the requested $card card; no files changed"
            }
        }
        if (goal.contains("Explore Minicoy", true) && !explicitlyRemove(goal, "Explore Minicoy")) {
            val cta = control.findAll(html).firstOrNull {
                visible(it.groupValues[3]).equals("Explore Minicoy", true)
            }
            require(cta != null) {
                "Website omitted the requested Explore Minicoy control; no files changed"
            }
            if (mustExplore && !alternateExplore.containsMatchIn(goal)) {
                require(cta.groupValues[1].equals("a", true)) {
                    "Explore Minicoy has no verified section link; no files changed"
                }
                val anchor = href.find(cta.groupValues[2])?.groupValues?.get(2)
                require(!anchor.isNullOrBlank()) {
                    "Explore Minicoy link has no section target; no files changed"
                }
                val section = heading.findAll(html).firstOrNull {
                    named(visible(it.groupValues[3]), "Things to Explore")
                }
                val sectionId = section?.let { id.find(it.groupValues[2])?.groupValues?.get(2) }
                require(sectionId == anchor &&
                    Regex("""(?is)\bid\s*=\s*(["'])${Regex.escape(anchor)}\1""")
                        .findAll(html).count() == 1 && css.contains(":target")) {
                    "Explore Minicoy target or visible feedback is missing; no files changed"
                }
                if (goal.contains("Exploring Minicoy!", ignoreCase = true)) {
                    require(html.contains("class=\"lyra-explore-feedback\"") &&
                        html.contains("Exploring Minicoy!") &&
                        css.contains(".lyra-explore-target:target + .lyra-explore-feedback")) {
                        "Requested Explore click text is missing; no files changed"
                    }
                }
            }
        }
        // Native anchor repair can make an old JS listener's ID stale. Verify after
        // all local repairs, before the existing atomic apply/rollback owner.
        return WorkspaceWebsiteScriptQuality.review(snapshot, files)
    }
}
