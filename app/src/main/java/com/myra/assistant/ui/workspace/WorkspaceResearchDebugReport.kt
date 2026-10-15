package com.myra.assistant.ui.workspace

/**
 * Debug-only, ephemeral report of the last research model audit.
 *
 * No file, preferences, database or Logcat writes. Retains just one report;
 * no original JSON, prompt, transcript, passages, provider key, URL or claim
 * text is persisted. Exact user-turn identity prevents displaying a report
 * for a different conversation or an old assistant response.
 */
internal class WorkspaceResearchDebugReport {
    private var lastTurnId: String? = null
    private var lastReport: String? = null

    @Synchronized fun clear() {
        lastTurnId = null
        lastReport = null
    }

    @Synchronized fun record(
        turnId: String,
        audit: WorkspaceResearchSynthesis.AuditResult,
        modelRoute: WorkspaceProviderRegistry.Id? = null,
    ) {
        if (turnId.isBlank()) return
        lastTurnId = turnId
        lastReport = summarize(audit, modelRoute)
    }

    /** Distinguish a model failure from a research turn that never called it. */
    enum class SkipReason {
        MISSING_COMPARISON_EVIDENCE,
        EVIDENCE_BUNDLE_REJECTED,
        FREE_PROVIDER_UNAVAILABLE,
        MODEL_REQUEST_NOT_PREPARED,
    }

    @Synchronized fun skipped(turnId: String, reason: SkipReason) {
        if (turnId.isBlank()) return
        lastTurnId = turnId
        lastReport = "LYRA Research Debug Report\n" +
            "Local test-only audit metadata; no raw JSON, chat text or keys.\n\n" +
            "Final audit: NOT_RUN\n" +
            "Research model stage: " + reason.name + "\n" +
            "No model claim decisions were available for this turn."
    }

    @Synchronized fun forTurn(turnId: String?): String? =
        lastReport?.takeIf { turnId != null && turnId == lastTurnId }

    companion object {
        fun summarize(
            audit: WorkspaceResearchSynthesis.AuditResult,
            modelRoute: WorkspaceProviderRegistry.Id? = null,
        ): String = buildString {
            appendLine("LYRA Research Debug Report")
            appendLine("Local test-only audit metadata; no original AI JSON or chat content.")
            appendLine()
            appendLine("Final audit: ${audit.issue.name}")
            if (modelRoute != null) {
                // Provider metadata only. openrouter/free is a changing pool,
                // so never report an invented underlying model name.
                val model = when (modelRoute) {
                    WorkspaceProviderRegistry.Id.GROQ_FREE ->
                        "Groq / openai/gpt-oss-120b"
                    WorkspaceProviderRegistry.Id.OPENROUTER_FREE ->
                        "OpenRouter / openrouter/free (dynamic model)"
                    else -> "Unsupported research provider"
                }
                appendLine("Model endpoint (attempted or observed): " + model)
            }
            val jsonState = when (audit.issue) {
                WorkspaceResearchSynthesis.AuditIssue.MALFORMED_RESPONSE -> "INVALID_JSON_OR_SCHEMA"
                WorkspaceResearchSynthesis.AuditIssue.MODEL_UNAVAILABLE -> "NO_MODEL_RESPONSE"
                else -> "VALID_STRUCTURED_RESPONSE"
            }
            appendLine("Model response format: ${jsonState}")
            audit.modelFailureReason?.let { reason ->
                appendLine("Model read failure: ${reason.name}")
            }
            appendLine("Model response characters: ${audit.modelResponseCharacters}")
            appendLine("Proposed claims: ${audit.claimDecisions.size}")
            appendLine("Accepted claims: ${audit.acceptedClaims}")
            appendLine("Rejected claims: ${audit.rejectedClaims}")
            val accepted = audit.claimDecisions.filter {
                it.result == WorkspaceResearchSynthesis.ClaimRejection.ACCEPTED
            }
            appendLine("Accepted subject 1 claims: " +
                accepted.count { it.namesFirstSubject })
            appendLine("Accepted subject 2 claims: " +
                accepted.count { it.namesSecondSubject })
            if (audit.claimDecisions.isEmpty()) {
                appendLine("No individual claim decisions available.")
            } else audit.claimDecisions.forEach { check ->
                val coverage = buildList {
                    if (check.namesFirstSubject) add("subject 1")
                    if (check.namesSecondSubject) add("subject 2")
                }.joinToString(", ").ifBlank { "none explicitly named" }
                appendLine()
                appendLine("Claim ${check.claimNumber}: ${check.result.name}")
                appendLine("  Mentioned: ${coverage}")
                appendLine("  Evidence IDs: " +
                    check.evidenceIds.joinToString(", ").ifBlank { "none valid" })
                appendLine("  Source quote submitted: ${check.providedQuote}")
            }
            appendLine()
            appendLine("This report describes the deterministic auditor's decision, " +
                "not proof that any claim is true.")
            append("To clear the local report, start another research request " +
                "or use Clear inside this dialog.")
        }.take(4_500)
    }
}
