package com.myra.assistant.ui.workspace

/**
 * Conservative user-intent gate for read-only Agent Reach.
 *
 * A GitHub URL is not automatically opened merely because it appears in conversation. The message
 * must be primarily the link itself or explicitly ask LYRA to read/check/inspect it.
 */
internal object WorkspaceAgentReachChatIntent {
    data class Decision(
        val target: WorkspaceAgentReachPolicy.Target? = null,
        val localError: String? = null,
    ) {
        init {
            require((target == null) xor (localError == null)) {
                "Agent Reach decision must be either a target or a local error"
            }
        }
    }

    private val url = Regex("""https://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val readIntent = Regex(
        """(?i)\b(?:check|read|inspect|review|open|analyse|analyze|summarise|summarize|""" +
            """dekh|dekho|dekhe|samjho)\b|\bcheck\s+k(?:a)?ro\b|\bcheck\s+kro\b"""
    )
    private val blockedIntent = Regex(
        """(?i)(?:\b(?:do\s+not|don't|dont)\s+(?:open|read|check|inspect|review|analy[sz]e)\b)|""" +
            """(?:\bmat\s+(?:khol|open|read|check|dekh|inspect)\b)|""" +
            """(?:\b(?:open|read|check|dekh|inspect)\s+mat\b)"""
    )

    private fun cleanUrl(raw: String): String =
        raw.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')

    fun decide(message: String): Decision? {
        if (blockedIntent.containsMatchIn(message)) return null
        val candidates = url.findAll(message)
            .map { cleanUrl(it.value) }
            .filter {
                runCatching {
                    WorkspaceAgentReachPolicy.parse(it).platform ==
                        WorkspaceAgentReachPolicy.Platform.GITHUB
                }.getOrDefault(false)
            }
            .distinct()
            .toList()
        if (candidates.isEmpty()) return null

        val remainder = candidates.fold(message) { text, link -> text.replace(link, " ") }
            .replace(Regex("""[\s\p{Punct}]+"""), " ").trim()
        val primarilyLink = remainder.isBlank()
        if (!primarilyLink && !readIntent.containsMatchIn(message)) return null
        if (candidates.size != 1) {
            return Decision(localError =
                "Agent Reach reads one GitHub link at a time. Nothing was opened.")
        }

        val parsed = runCatching { WorkspaceAgentReachPolicy.parse(candidates.single()) }
            .getOrElse {
                return Decision(localError =
                    (it.message ?: "This GitHub URL is not accepted for read-only Agent Reach."))
            }
        return runCatching {
            WorkspaceAgentReachGitHub.selection(parsed)
            Decision(target = parsed)
        }.getOrElse {
            Decision(localError =
                (it.message ?: "This GitHub link type is not supported by read-only Agent Reach yet."))
        }
    }
}
