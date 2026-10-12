package com.myra.assistant.ui.workspace

import org.json.JSONObject

/**
 * Read-only intent planning by LYRA's already selected Free Chat model.
 *
 * The model MAY recommend public research, but cannot authorize a connector,
 * write, permission grant, download, install, or any tool execution. Actual
 * public research still runs through the existing verified read-only pipeline.
 * A failed/invalid proposal returns to normal model chat, never keyword routing.
 */
internal object WorkspaceIntelligentUnderstanding {
    enum class Route { CHAT, PUBLIC_RESEARCH }
    data class Decision(val route: Route, val query: String = "")

    const val SYSTEM_INSTRUCTIONS =
        "You are LYRA's semantic turn planner, not the final assistant response. " +
        "Understand the user's ACTUAL intended outcome, including informal Hinglish, " +
        "misspellings, comparisons and follow-up context, before selecting a route. " +
        "Decide based on meaning, NOT matching example words or individual keywords. " +
        "Return ONLY a valid JSON object with keys route and searchQuery: " +
        """{"route":"CHAT","searchQuery":""} or {"route":"PUBLIC_RESEARCH","searchQuery":"topic query"}.""" +
        " Select CHAT for greetings, conversation, emotional support, creative requests, " +
        "general knowledge that you can responsibly explain, coding explanations, " +
        "troubleshooting, requests needing user clarification, and questions about " +
        "the user's own conversation or connected/private state. " +
        "Select PUBLIC_RESEARCH when answering accurately requires finding fresh " +
        "or independently verifiable PUBLIC webpage evidence, including comparisons " +
        "where the user asks for sources. Never route a request to write or change " +
        "anything, access private records, verify the user's own account, perform " +
        "a purchase, or download/install an app to PUBLIC_RESEARCH. " +
        "The route does not grant any tool permission. For PUBLIC_RESEARCH, provide " +
        "a compact neutral searchQuery containing the real subjects and the central " +
        "topic (for example, battery optimization), not LYRA's name or user filler. " +
        "Never manufacture a URL, personal identity, factual answer, citation or " +
        "new named subject. When in doubt, choose CHAT so the regular assistant " +
        "can answer or ask one clarification. No explanation or Markdown outside JSON."

    /**
     * Fast trivial-chat path is purely a latency budget, not an intent/keyword
     * classifier. Everything substantive can use the semantic model.
     */
    fun shouldPlan(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length in 18..2_000 &&
            !trimmed.contains('\u0000') &&
            !Regex("""(?i)https?://""").containsMatchIn(trimmed) &&
            !WorkspaceSourceContext.containsPossibleSecret(trimmed)
    }

    fun parse(raw: String): Decision? {
        if (raw.length !in 12..1_200) return null
        val value = raw.trim().replace(Regex("""(?is)^\x60{3}(?:json)?\s*"""), "")
            .replace(Regex("""(?is)\s*\x60{3}$"""), "").trim()
        val obj = runCatching { JSONObject(value) }.getOrNull() ?: return null
        return when (obj.optString("route").uppercase()) {
            "CHAT" -> Decision(Route.CHAT)
            "PUBLIC_RESEARCH" -> {
                val query = obj.optString("searchQuery").trim()
                    .replace(Regex("""\s+"""), " ")
                if (query.length !in 5..180 || query.contains("://") ||
                    query.any { it.isISOControl() } ||
                    WorkspaceSourceContext.containsPossibleSecret(query)) null
                else Decision(Route.PUBLIC_RESEARCH, query)
            }
            else -> null
        }
    }
}
