package com.myra.assistant.ui.workspace

/**
 * Deterministic connected-repository HEAD read intent. No AI interpretation can turn a branch
 * status question (or its negated action examples) into write authority.
 *
 * Only the currently paired feature branch and the default "main" branch are supported by
 * this small live-HEAD route; other branch names fail closed at the runner's binding check.
 */
internal object WorkspaceConnectedGitHubHeadsIntent {
    data class Decision(
        val feature: Boolean,
        val main: Boolean,
        val namedFeatureBranch: String? = null,
        val namedRepository: String? = null,
    ) {
        init { require(feature || main) }
    }

    private val url = Regex("""https?://""", RegexOption.IGNORE_CASE)
    private val head = Regex("""(?iu)\b(?:head|sha|commit\s+(?:hash|id))\b""")
    private val read = Regex(
        """(?iu)\b(?:fetch|show|get|list|read|check|verify|tell|what|which|dekh\p{L}*|bata\p{L}*)\b"""
    )
    private val context = Regex("""(?iu)\b(?:github|repo(?:sitory)?|branch|commit)\b""")
    private val main = Regex("""(?iu)\bmain\b""")
    private val feature = Regex("""(?iu)\b(?:feature|connected|current)\s+branch\b""")
    private val branchName = Regex("""(?iu)\b(?:agent|feature|dev|release|hotfix)/[a-z0-9._/-]+\b""")
    private val repositoryName = Regex(
        """(?iu)\b(?:in|repository|repo)\s*:?\s+([a-z0-9_.-]+/[a-z0-9_.-]+)\b"""
    )
    private val boundary = Regex(
        """(?iu)\b(?:do\s+not|don't|dont|never|without|if\s+the\s+(?:live|read))\b"""
    )

    fun decide(raw: String): Decision? {
        val text = raw.trim().replace('’', '\'')
            .replace(Regex("""[\s\p{Z}]+"""), " ")
        if (text.length !in 1..1_500 || url.containsMatchIn(text) ||
            WorkspaceSourceContext.containsPossibleSecret(text)) return null
        if (!head.containsMatchIn(text) || !read.containsMatchIn(text) ||
            !context.containsMatchIn(text)) return null
        val intent = WorkspaceSemanticTurnIntent.propose(text)
        if (intent.effect != WorkspaceSemanticTurnIntent.Effect.READ) return null

        // Only the affirmative read clause defines requested targets. "Do not modify main"
        // must never turn main into an additional requested branch.
        val ask = boundary.split(text, limit = 2).first().trim()
        if (ask.isEmpty() || !head.containsMatchIn(ask)) return null
        val askMain = main.containsMatchIn(ask)
        val named = branchName.findAll(ask).map { it.value }.distinct().toList()
        if (named.size > 1) return null
        val askFeature = !askMain || feature.containsMatchIn(ask) || named.isNotEmpty()
        val repoNames = repositoryName.findAll(ask).map { it.groupValues[1] }
            .filterNot { it == named.singleOrNull() }.distinct().toList()
        if (repoNames.size > 1) return null
        return Decision(
            feature = askFeature,
            main = askMain,
            namedFeatureBranch = named.singleOrNull(),
            namedRepository = repoNames.singleOrNull(),
        )
    }

    fun receipt(completion: WorkspaceConnectedGitHubHeadsRunner.Completion): String =
        buildString {
            appendLine("Bro, current branch HEADs check ho gaye:")
            appendLine(completion.repository)
            completion.branches.forEach { branch ->
                appendLine("- " + branch.name + ": " + branch.headSha)
            }
        }.trim()
}
