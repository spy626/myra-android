package com.myra.assistant.ui.workspace

/**
 * Exact proposal grounding for explicit USER approval.
 *
 * The USER may name one current Proposal ID directly, or approve one exact Proposal ID visible in
 * recent assistant messages. Multiple visible proposals are intentionally ambiguous.
 */
internal object WorkspaceWorkflowImprovementApprovalGrounding {
    fun resolve(
        userText: String,
        recentAssistantTexts: List<String>,
        proposals: List<WorkspaceWorkflowImprovementProposal.Proposal>,
    ): WorkspaceWorkflowImprovementProposal.Proposal? {
        if (proposals.isEmpty()) return null
        val byId = proposals.associateBy { it.id }

        // Any explicit Proposal ID is authoritative: NEVER fall back to another visible
        // proposal when the typed ID is stale, malformed relative to current state or ambiguous.
        val mentionedIds = Regex("""workflow-proposal:[0-9a-f]{64}""")
            .findAll(userText).map { it.value }.distinct().toList()
        if (mentionedIds.isNotEmpty()) {
            return mentionedIds.singleOrNull()?.let(byId::get)
        }
        // Incomplete IDs are also explicit attempts and must fail closed.
        if (userText.contains("workflow-proposal:", ignoreCase = true)) return null

        val visible = recentAssistantTexts
            .takeLast(6)
            .flatMap { text -> byId.keys.filter(text::contains) }
            .distinct()
        return if (visible.size == 1) byId[visible.single()] else null
    }
}
