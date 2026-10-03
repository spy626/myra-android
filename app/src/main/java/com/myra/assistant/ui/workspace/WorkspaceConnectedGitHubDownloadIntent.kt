package com.myra.assistant.ui.workspace

/**
 * Resolves read-only download requests against the CURRENT selected chat.
 * Explicit build identifiers win; deictic references use only the immediately previous
 * verified-build reply and its user turn. The runner re-verifies all references LIVE.
 */
internal object WorkspaceConnectedGitHubDownloadIntent {
    data class Decision(val runNumber: Long? = null, val localError: String? = null) {
        init { require((runNumber == null) xor (localError == null)) }
    }
    private val artifact = Regex(
        """(?iu)\b(?:apk|artifact|download|installer|release|install(?:ation)?|file)\b"""
    )
    private val request = Regex(
        """(?iu)\b(?:link|url|download|send|share|give|fetch|get|show|where|bhej\p{L}*|de\s+do|dedo|bata\p{L}*|dikha\p{L}*|mil\p{L}*)\b"""
    )
    private val number = Regex("""(?<![\p{L}\d_./-])#?(\d{3,9})(?![\p{L}\d_/-]|\.\d)""")
    private val priorLink = Regex(
        """(?iu)\]\(https://github\.com/[a-z0-9_.-]+/[a-z0-9_.-]+/actions/runs/([1-9]\d{0,18})\)"""
    )
    private val receiptNumber = Regex("""(?iu)\bBuild\s*#(\d{3,9})\b""")
    private val boundary = Regex(
        """(?iu)\b(?:do\s+not|don't|dont|never|without|kuch\s+change\s+mat)\b"""
    )

    fun decide(raw: String, preceding: List<WorkspaceConversationStore.Message>): Decision? {
        val text = raw.trim()
        if (text.length !in 1..1_500 || "://" in text ||
            WorkspaceSourceContext.containsPossibleSecret(text)) return null
        val asked = boundary.find(text)?.let { text.substring(0, it.range.first) } ?: text
        if (!artifact.containsMatchIn(asked) || !request.containsMatchIn(asked)) return null
        // In "build #3374 ka APK", build names an EXISTING run, not permission
        // to create a build. Mask only number-bound nouns during the mutation scan.
        // All independent edit/create/build imperatives still reach the authority gate.
        val mutationScanText = Regex("""(?iu)\bbuild\s*#?\s*\d{3,9}\b""")
            .replace(text, "verified run reference")
        val semantic = WorkspaceSemanticTurnIntent.propose(mutationScanText)
        if (semantic.effect == WorkspaceSemanticTurnIntent.Effect.WRITE ||
            semantic.kind == WorkspaceSemanticTurnIntent.Kind.CAPABILITY_QUERY ||
            WorkspaceExecutionAuthority.allowsCodingMutation(mutationScanText)) return null
        val numbers = number.findAll(asked).mapNotNull {
            it.groupValues[1].toLongOrNull()
        }.distinct().toList()
        if (numbers.size > 1) return Decision(
            localError = "Bro, kaunse build ka APK chahiye? Ek build number bata do."
        )
        numbers.singleOrNull()?.let { return Decision(runNumber = it) }

        // Do not import historical, other-chat or generic model-suggested URLs as authority.
        val assistant = preceding.lastOrNull()
        val user = preceding.getOrNull(preceding.lastIndex - 1)
        if (assistant?.role != "assistant" || user?.role != "user") return Decision(
            localError = "Bro, kis build ka APK chahiye? Build number bata do."
        )
        val precedingNumber = WorkspaceConnectedGitHubRunIntent.decide(user.text)?.runNumber
        val replyNumbers = receiptNumber.findAll(assistant.text)
            .mapNotNull { it.groupValues[1].toLongOrNull() }.distinct().toList()
        if (precedingNumber == null || replyNumbers != listOf(precedingNumber) ||
            !priorLink.containsMatchIn(assistant.text)) return Decision(
            localError = "Bro, kis verified build ka APK chahiye? Number bata do."
        )
        return Decision(runNumber = precedingNumber)
    }

    fun receipt(value: WorkspaceConnectedGitHubDownloadRunner.Completion): String = buildString {
        appendLine("Haan bro 😂 **Build #${value.run.runNumber} ka direct APK mil gaya!**")
        appendLine()
        appendLine("[⬇ Download LYRA Test APK #${value.run.runNumber}](${value.apkUrl})")
        appendLine()
        append("Verified GitHub release · direct APK, ZIP extract karne ki zarurat nahi.")
    }
}