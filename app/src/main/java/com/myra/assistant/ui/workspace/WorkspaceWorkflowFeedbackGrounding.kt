package com.myra.assistant.ui.workspace

/**
 * Exact selected-chat grounding for workflow feedback.
 *
 * Only evidence already rendered in assistant messages can bind feedback to the recent verified
 * workflow receipt. Similar wording or a different CI number is never enough.
 */
internal object WorkspaceWorkflowFeedbackGrounding {
    fun exactReceiptVisible(
        recentAssistantTexts: List<String>,
        receipt: WorkspaceRecentGitHubActionReceipt.Receipt,
    ): Boolean {
        val ciMarker = "CI #" + receipt.ciRunNumber
        return recentAssistantTexts
            .takeLast(6)
            .any { text ->
                text.contains(ciMarker) || text.contains(receipt.ciUrl)
            }
    }
}
