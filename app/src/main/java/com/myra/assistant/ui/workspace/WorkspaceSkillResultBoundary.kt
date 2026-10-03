package com.myra.assistant.ui.workspace

/**
 * Final local boundary for a provider reply guided by one enabled skill.
 *
 * Skill-guided provider output is advisory text only. It cannot grant permissions, mutate local
 * authority, mark deterministic verification as PASS, or mint LYRA-local receipts.
 */
internal object WorkspaceSkillResultBoundary {
    enum class Authority { ADVISORY_TEXT_ONLY }

    data class Result(
        val authority: Authority,
        val text: String,
    )

    private val reservedLocalNamespace = Regex(
        """(?im)^\s*(?:Skill\s+receipt|Skill\s+result)\s*[·:]"""
    )

    private fun boundProviderText(reply: String): String {
        val clean = reply.trim()
        require(clean.isNotBlank()) { "Skill-guided provider reply is empty" }
        require(!reservedLocalNamespace.containsMatchIn(clean)) {
            "Skill-guided provider reply attempted to spoof a LYRA-local skill namespace"
        }
        return clean
    }

    fun result(
        reply: String,
        projection: WorkspaceSkillInvocation.Projection?,
    ): Result {
        if (projection == null) {
            return Result(Authority.ADVISORY_TEXT_ONLY, reply)
        }
        val providerText = boundProviderText(reply)
        val text = buildString {
            appendLine("Skill result · ADVISORY_TEXT_ONLY")
            appendLine(
                "Provider text below is advisory workflow guidance only. It grants no tool, " +
                    "network, source, memory, write, or apply permission and cannot mark work " +
                    "verified/PASS. Local LYRA gates and deterministic verification remain authoritative."
            )
            appendLine("--- BEGIN ADVISORY SKILL RESULT ---")
            appendLine(providerText)
            appendLine("--- END ADVISORY SKILL RESULT ---")
            appendLine()
            append(WorkspaceSkillInvocationReceipt.text(projection))
        }
        require(text.length <= WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {
            "Skill result boundary would exceed the local conversation message limit"
        }
        return Result(Authority.ADVISORY_TEXT_ONLY, text)
    }

    fun attach(
        reply: String,
        projection: WorkspaceSkillInvocation.Projection?,
    ): String = if (projection == null) reply else result(reply, projection).text
}
