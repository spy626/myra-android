package com.myra.assistant.agent

/** Shared query extraction for the unified final intent and its service handoff. */
object FinalSearchHandoff {
    fun parse(raw: String): BrowserSearchRequest? {
        val text = raw.trim().trimEnd('.', '?', '!', '।').trim()
        BrowserSearchRequestParser.parse(text)?.let { return it }
        // Verb + imperative separates the requested query from surrounding address/context.
        // This fallback is used only after unified action authorization at dispatch.
        val predicate = Regex("\\b(?:search|find|dhundo|dhoondo|khojo)\\s+(?:karo|kar do)\\b", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val after = text.substring(predicate.range.last + 1).trim()
        val before = text.substring(0, predicate.range.first).trim()
        val query = after.ifBlank { before }.takeIf { it.length in 2..300 } ?: return null
        return BrowserSearchRequest(query)
    }
}

object SearchProposalPolicy {
    const val DECISION = "WAIT_FOR_FINAL"
    const val MAY_EXECUTE = false
    const val MAY_REPORT_FAILURE = false
}
