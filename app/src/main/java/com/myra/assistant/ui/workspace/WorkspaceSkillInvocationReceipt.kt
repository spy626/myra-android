package com.myra.assistant.ui.workspace

/**
 * Deterministic local receipt for one explicit skill invocation.
 *
 * The provider never writes this receipt. It contains fingerprints only, never skill body, keys,
 * source, memory, permission payloads or other secret-bearing content.
 */
internal object WorkspaceSkillInvocationReceipt {
    private val sha256 = Regex("""[0-9a-f]{64}""")
    private val name = Regex("""[a-z0-9][a-z0-9-]{0,63}""")

    fun text(projection: WorkspaceSkillInvocation.Projection): String {
        require(name.matches(projection.skillName)) { "Skill receipt name is invalid" }
        require(projection.origin == WorkspaceSkillInvocation.Origin.USER_EXPLICIT) {
            "This receipt phase supports explicit user skill invocation only"
        }
        require(listOf(
            projection.contentSha256,
            projection.packageSha256,
            projection.permissionSha256,
            projection.activationBindingSha256,
            projection.invocationSha256,
        ).all(sha256::matches)) { "Skill receipt hash is invalid" }
        require(projection.taskId.isNotBlank() && projection.turnId.isNotBlank()) {
            "Skill receipt task/turn binding is missing"
        }

        fun short(value: String) = value.take(12)
        return buildString {
            appendLine("Skill receipt · ${projection.skillName} · one turn")
            appendLine("content ${short(projection.contentSha256)} · package ${short(projection.packageSha256)}")
            appendLine("permissions ${short(projection.permissionSha256)} · activation ${short(projection.activationBindingSha256)}")
            appendLine("invocation ${short(projection.invocationSha256)}")
            append("Local LYRA receipt only — not proof of task completion or verification.")
        }
    }

    fun attach(
        reply: String,
        projection: WorkspaceSkillInvocation.Projection?,
    ): String {
        if (projection == null) return reply
        val clean = reply.trim()
        require(clean.isNotBlank()) { "Skill reply is empty" }
        val combined = "$clean\n\n${text(projection)}"
        require(combined.length <= WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {
            "Skill receipt would exceed the local conversation message limit"
        }
        return combined
    }
}
