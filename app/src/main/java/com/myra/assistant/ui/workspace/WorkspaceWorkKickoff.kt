package com.myra.assistant.ui.workspace

/**
 * Immediate, presentation-only acknowledgement for protected GitHub work.
 *
 * It summarizes only the current USER request. It never grants authority, calls a model,
 * claims success, or changes the execution plan.
 */
internal object WorkspaceWorkKickoff {
    private const val MAX_CHARS = 360

    private val fileRef = Regex(
        """(?i)(?<![A-Za-z0-9_.-])((?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\.(?:md|txt|kt|kts|java|py|js|mjs|cjs|ts|tsx|jsx|json|jsonc|yaml|yml|toml|xml|gradle|properties|rs|go|swift|c|cc|cpp|h|hpp|css|html|htm))(?![A-Za-z0-9_.-])"""
    )
    private val hinglish = Regex(
        """(?iu)\b(?:bro|karo|kro|karna|kar|hai|hain|me|mein|mai|sirf|tak|wala|wali|hatao|jodo|rakho|batao)\b"""
    )
    private val commentOnly = Regex("""(?iu)\bcomment(?:-only| only)?\b""")
    private val remove = Regex("""(?iu)\b(?:remove|delete|hatao|hata|nikalo)\b""")
    private val add = Regex("""(?iu)\b(?:add|insert|jodo|lagao)\b""")
    private val exactCi = Regex("""(?iu)\b(?:exact\s+ci|ci\s+green|ci\s+tak|actions\s+green)\b""")
    private val featureBranch = Regex("""(?iu)\b(?:feature\s+branch|agent/myra-phase-1)\b""")

    fun github(request: String): String {
        val clean = request.trim()
        require(clean.isNotBlank() && clean.length <= WorkspaceLongInputPolicy.MAX_MESSAGE_CHARS) {
            "Kickoff request is invalid"
        }
        val sensitive = WorkspaceSourceContext.containsPossibleSecret(clean)
        val files = if (sensitive) emptyList() else fileRef.findAll(clean)
            .map { it.groupValues[1].substringAfterLast('/') }
            .distinctBy { it.lowercase() }
            .take(2)
            .toList()
        val isHinglish = hinglish.containsMatchIn(clean)
        val change = when {
            sensitive -> null
            commentOnly.containsMatchIn(clean) && remove.containsMatchIn(clean) ->
                if (isHinglish) "requested comment-only cleanup" else "requested comment-only cleanup"
            commentOnly.containsMatchIn(clean) && add.containsMatchIn(clean) ->
                if (isHinglish) "requested safe comment-only change" else "requested safe comment-only change"
            commentOnly.containsMatchIn(clean) ->
                if (isHinglish) "requested comment-only change" else "requested comment-only change"
            remove.containsMatchIn(clean) ->
                if (isHinglish) "requested removal" else "requested removal"
            add.containsMatchIn(clean) ->
                if (isHinglish) "requested addition" else "requested addition"
            else ->
                if (isHinglish) "requested GitHub change" else "requested GitHub change"
        }

        val target = when (files.size) {
            0 -> if (isHinglish) "bounded repo scope me" else "within the bounded repository scope"
            1 -> if (isHinglish) "${files.single()} me" else "in ${files.single()}"
            else -> if (isHinglish) "${files.joinToString(" aur ")} ke scope me"
                else "across ${files.joinToString(" and ")}"
        }

        val first = if (isHinglish) {
            if (change == null) {
                "Haan bro 👍 main requested GitHub task ko safe bounded scope me check kar raha hoon."
            } else {
                "Haan bro 👍 main $target $change handle kar raha hoon."
            }
        } else {
            if (change == null) {
                "Got it 👍 I’m checking this GitHub task within the safe bounded scope."
            } else {
                "Got it 👍 I’m handling the $change $target."
            }
        }

        val constraints = buildList {
            if (featureBranch.containsMatchIn(clean)) {
                add(if (isHinglish) "feature branch par hi rakhunga" else "keep it on the feature branch")
            }
            if (exactCi.containsMatchIn(clean)) {
                add(if (isHinglish) "exact CI result verify karke bataunga"
                    else "verify the exact CI result before calling it done")
            }
        }
        val second = when {
            constraints.isEmpty() -> ""
            isHinglish -> " " + constraints.joinToString(" aur ").replaceFirstChar { it.uppercase() } + "."
            else -> " I’ll " + constraints.joinToString(" and ") + "."
        }

        val result = WorkspaceWorkTrace.safeText(first + second, MAX_CHARS)
        require(result.isNotBlank() && result.length <= MAX_CHARS) { "Kickoff text is invalid" }
        return result
    }
}
