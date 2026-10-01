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

        val userMatches = byId.keys.filter(userText::contains).distinct()
        if (userMatches.size == 1) return byId[userMatches.single()]
        if (userMatches.size > 1) return null

        val visible = recentAssistantTexts
            .takeLast(6)
            .flatMap { text -> byId.keys.filter(text::contains) }
            .distinct()
        return if (visible.size == 1) byId[visible.single()] else null
    }
}
