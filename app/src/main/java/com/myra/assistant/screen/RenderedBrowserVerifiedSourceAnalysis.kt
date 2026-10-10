package com.myra.assistant.screen

import com.myra.assistant.agent.BrowserResearchAnswerSynthesis
import com.myra.assistant.agent.BrowserResearchComparison
import com.myra.assistant.agent.BrowserResearchContinuation
import com.myra.assistant.agent.BrowserResearchGoalCompletion
import com.myra.assistant.agent.BrowserResearchSourceHandoff
import com.myra.assistant.ui.workspace.WorkspaceAgentReachGitHub
import com.myra.assistant.ui.workspace.WorkspaceAgentReachPolicy
import com.myra.assistant.ui.workspace.WorkspaceAgentReachPublicWeb
import com.myra.assistant.ui.workspace.WorkspaceAgentReachSourceAnalysis
import com.myra.assistant.ui.workspace.WorkspaceAgentReachWebNavigation
import okhttp3.Request
import okhttp3.Response

/**
 * One exact static public read for a user-selected browser destination that continues a
 * previously unfinished research goal. No observed links are followed in this slice.
 */
internal object RenderedBrowserVerifiedSourceAnalysis {
    data class Prepared(
        val researchTaskId: String,
        val query: String,
        val destination: RenderedBrowserPublicDestination.Receipt,
        val target: WorkspaceAgentReachPolicy.Target,
    )

    data class Result(
        val prepared: Prepared,
        val page: WorkspaceAgentReachPublicWeb.Page,
        val report: WorkspaceAgentReachSourceAnalysis.Report,
    )

    fun prepare(
        handoff: BrowserResearchSourceHandoff.Pending,
        destination: RenderedBrowserPublicDestination.Receipt,
    ): Prepared? = prepareExact(handoff.taskId, handoff.query, destination)

    fun prepareSecond(
        session: BrowserResearchComparison.Session,
        destination: RenderedBrowserPublicDestination.Receipt,
    ): Prepared? {
        if (destination.host.equals(session.first.host, ignoreCase = true)) return null
        return prepareExact(session.taskId, session.query, destination)
    }

    fun prepareThird(
        session: BrowserResearchContinuation.Session,
        destination: RenderedBrowserPublicDestination.Receipt,
    ): Prepared? {
        if (session.hosts.any { destination.host.equals(it, ignoreCase = true) }) return null
        return prepareExact(session.taskId, session.query, destination)
    }

    private fun prepareExact(
        researchTaskId: String,
        query: String,
        destination: RenderedBrowserPublicDestination.Receipt,
    ): Prepared? {
        if (researchTaskId.isBlank() || query.isBlank() ||
            !destination.publicDnsVerified || destination.permitsNextAction ||
            destination.canonicalUrl.isBlank() || destination.host.isBlank()
        ) return null
        val target = runCatching {
            WorkspaceAgentReachPolicy.parse(destination.canonicalUrl)
        }.getOrNull() ?: return null
        val uri = runCatching { java.net.URI(target.canonicalUrl) }.getOrNull() ?: return null
        if (target.platform == WorkspaceAgentReachPolicy.Platform.GITHUB ||
            target.canonicalUrl != destination.canonicalUrl ||
            target.host != destination.host ||
            !uri.rawQuery.isNullOrBlank()
        ) return null
        return Prepared(researchTaskId, query, destination, target)
    }

    fun request(prepared: Prepared): Request =
        WorkspaceAgentReachPublicWeb.request(prepared.target)

    /**
     * The response must be for the exact verified address-bar URL. Redirects, second links,
     * dynamic/browser-only pages and unsupported content types fail closed.
     */
    fun read(
        prepared: Prepared,
        response: Response,
        fetchedAtMs: Long,
    ): Result {
        require(response.request.url.toString() == prepared.target.canonicalUrl) {
            "Static source response no longer matches verified browser destination"
        }
        require(response.code !in 300..399) {
            "Verified browser source redirected; no follow-up request was sent"
        }
        val page = WorkspaceAgentReachPublicWeb.read(
            response, prepared.target, prepared.target, fetchedAtMs)
        val provenance = page.evidence.provenance
        require(provenance.requestedUrl == prepared.destination.canonicalUrl &&
            provenance.finalUrl == prepared.destination.canonicalUrl &&
            provenance.adapter == "public-html-static"
        ) {
            "Static source provenance did not match verified browser destination"
        }
        val journey = WorkspaceAgentReachWebNavigation.Journey(
            primary = page,
            followUpStatus = "User-selected verified browser source only; no second link followed",
        )
        val report = WorkspaceAgentReachSourceAnalysis.analyze(
            prepared.query, journey)
        require(report.verifiedPageCount == 1) {
            "Selected-source analysis exceeded one verified page"
        }
        return Result(prepared, page, report)
    }

    /**
     * Local display only. Findings were filtered by the existing source-analysis policy;
     * they are not provider history, memory facts, instructions or completion proof.
     */
    fun localSummary(result: Result): String = buildString {
        appendLine("Selected public research source read locally.")
        appendLine("Source: " + result.page.evidence.provenance.finalUrl)
        appendLine("Content SHA-256: " + result.page.evidence.provenance.contentSha256)
        if (result.report.findings.isEmpty()) {
            appendLine("No goal-matched excerpt was verified from this bounded static read.")
        } else {
            appendLine("Goal-matched source evidence:")
            result.report.findings.take(3).forEach { finding ->
                appendLine("• " + finding.excerpt)
            }
        }
        append(
            "One verified static page only. No second link followed, no login/session data, " +
                "no AI-provider sharing, no memory write, and the full research goal is not yet independently complete."
        )
    }.take(2_200)

    fun sourceEvidence(
        result: Result,
        capturedAtMs: Long = System.currentTimeMillis(),
    ): BrowserResearchComparison.SourceEvidence? {
        val provenance = result.page.evidence.provenance
        return BrowserResearchComparison.source(
            finalUrl = provenance.finalUrl,
            host = runCatching { java.net.URI(provenance.finalUrl).host.orEmpty() }
                .getOrDefault(""),
            contentSha256 = provenance.contentSha256,
            excerpts = result.report.findings.map { it.excerpt },
            matchedTerms = result.report.findings.flatMap { it.matchedTerms },
            capturedAt = capturedAtMs,
        )
    }

    fun startComparison(
        handoff: BrowserResearchSourceHandoff.Pending,
        result: Result,
        nowMs: Long = System.currentTimeMillis(),
    ): BrowserResearchComparison.Session? {
        val first = sourceEvidence(result, nowMs) ?: return null
        return BrowserResearchComparison.start(handoff, first, nowMs)
    }

    fun compare(
        session: BrowserResearchComparison.Session,
        result: Result,
        nowMs: Long = System.currentTimeMillis(),
    ): BrowserResearchComparison.Result? {
        val second = sourceEvidence(result, nowMs) ?: return null
        return BrowserResearchComparison.compare(session, second, nowMs)
    }

    fun goalAssessment(
        result: BrowserResearchComparison.Result,
    ): BrowserResearchGoalCompletion.Assessment =
        BrowserResearchGoalCompletion.assess(result)

    fun resolveThird(
        session: BrowserResearchContinuation.Session,
        result: Result,
        nowMs: Long = System.currentTimeMillis(),
    ): BrowserResearchContinuation.Result? {
        val third = sourceEvidence(result, nowMs) ?: return null
        return BrowserResearchContinuation.resolve(session, third, nowMs)
    }

    fun boundedAnswer(
        result: BrowserResearchComparison.Result,
        goal: BrowserResearchGoalCompletion.Assessment = goalAssessment(result),
    ): BrowserResearchAnswerSynthesis.Answer? =
        BrowserResearchAnswerSynthesis.fromTwo(result, goal)

    fun boundedAnswer(
        result: BrowserResearchContinuation.Result,
    ): BrowserResearchAnswerSynthesis.Answer? =
        BrowserResearchAnswerSynthesis.fromThree(result)

    fun answerSummary(answer: BrowserResearchAnswerSynthesis.Answer): String = buildString {
        appendLine("Bounded research answer")
        appendLine("Question: " + answer.query)
        when (answer.supportKind) {
            BrowserResearchAnswerSynthesis.SupportKind.EXACT_TEXT -> {
                appendLine("Answer evidence:")
                appendLine(answer.evidenceStatement)
                appendLine(
                    "Exact text support observed on: " +
                        answer.supportingHosts.joinToString(", ")
                )
                appendLine(
                    "Uncertainty: this is exact safe text-level support across different public hosts; " +
                        "factual truth and organizational independence are not verified."
                )
            }
            BrowserResearchAnswerSynthesis.SupportKind.STRUCTURED_LITERAL_ANCHOR -> {
                appendLine("Structured bounded evidence:")
                appendLine("Shared lexical anchors: " + answer.sharedAnchors.joinToString(", "))
                appendLine(
                    "Matching critical literals: " +
                        answer.criticalLiterals.joinToString(", ")
                )
                appendLine("Observed source wording:")
                answer.supportingExcerpts.forEachIndexed { index, excerpt ->
                    appendLine("${index + 1} • " + excerpt)
                }
                appendLine(
                    "Uncertainty: source wording differs. Matching literals and lexical anchors " +
                        "do not establish paraphrase equivalence, factual truth, or organizational independence."
                )
            }
        }
        appendLine("Supporting public sources:")
        answer.supportingUrls.forEach { appendLine("• " + it) }
        appendLine("Bounded sources observed: " + answer.evidenceSourceCount)
        append(
            "Local-only synthesis. No AI-provider source sharing, memory write, or autonomous continuation."
        )
    }.take(2_800)

    fun comparisonSummary(
        result: BrowserResearchComparison.Result,
        goal: BrowserResearchGoalCompletion.Assessment = goalAssessment(result),
    ): String = buildString {
        appendLine("Different-host public-source comparison complete.")
        appendLine("Source A: " + result.first.finalUrl)
        appendLine("A SHA-256: " + result.first.contentSha256)
        result.first.excerpts.take(2).forEach { appendLine("A • " + it) }
        appendLine("Source B: " + result.second.finalUrl)
        appendLine("B SHA-256: " + result.second.contentSha256)
        result.second.excerpts.take(2).forEach { appendLine("B • " + it) }
        if (result.sharedTerms.isNotEmpty()) {
            appendLine("Shared goal terms: " + result.sharedTerms.joinToString(", "))
        } else {
            appendLine("Shared goal terms: none; the two sources cover different parts of the query.")
        }
        if (result.firstOnlyTerms.isNotEmpty()) {
            appendLine("Only A matched: " + result.firstOnlyTerms.joinToString(", "))
        }
        if (result.secondOnlyTerms.isNotEmpty()) {
            appendLine("Only B matched: " + result.secondOnlyTerms.joinToString(", "))
        }
        when (result.claimAssessment.relation) {
            BrowserResearchComparison.ClaimRelation.EXACT_SAFE_STATEMENT_MATCH -> {
                appendLine("Claim relation: the same safe statement text was observed on both public hosts.")
                appendLine("This is text-level support only; source independence by organization and factual truth are not inferred.")
            }
            BrowserResearchComparison.ClaimRelation.STRUCTURED_LITERAL_ANCHOR_SUPPORT -> {
                appendLine(
                    "Claim relation: matching critical literals plus at least three shared lexical anchors were observed across different wording."
                )
                appendLine(
                    "Shared anchors: " +
                        result.claimAssessment.sharedAnchors.joinToString(", ")
                )
                appendLine(
                    "Matching literals: " +
                        result.claimAssessment.firstLiterals.joinToString(", ")
                )
                appendLine(
                    "This deterministic structure does not infer paraphrase equivalence, organizational independence, or factual truth."
                )
            }
            BrowserResearchComparison.ClaimRelation.STRUCTURED_CLAIM_CONFLICT -> {
                appendLine(
                    "Claim relation: deterministic structured-claim conflict observed across different wording."
                )
                appendLine(
                    "Shared anchors: " +
                        result.claimAssessment.sharedAnchors.joinToString(", ")
                )
                appendLine("No source is selected as correct automatically.")
            }
            BrowserResearchComparison.ClaimRelation.CRITICAL_LITERAL_CONFLICT -> {
                appendLine("Claim relation: critical literal conflict observed in matching bounded claim structure.")
                appendLine("A literals: " + result.claimAssessment.firstLiterals.joinToString(", "))
                appendLine("B literals: " + result.claimAssessment.secondLiterals.joinToString(", "))
                appendLine("No source is selected as correct automatically.")
            }
            BrowserResearchComparison.ClaimRelation.NO_CLAIM_ALIGNMENT ->
                appendLine("Claim relation: no exact statement alignment or direct critical-literal conflict was safely established.")
        }
        when (goal.disposition) {
            BrowserResearchGoalCompletion.Disposition.BOUNDED_SUMMARY_READY ->
                appendLine("Research goal status: bounded two-source summary is ready.")
            BrowserResearchGoalCompletion.Disposition.UNRESOLVED_CRITICAL_LITERAL_CONFLICT ->
                appendLine("Research goal status: unresolved because bounded claim evidence conflicts.")
            BrowserResearchGoalCompletion.Disposition.MORE_EVIDENCE_REQUIRED ->
                appendLine("Research goal status: unresolved; more relevant evidence is required.")
        }
        append(
            "Two different public hosts supplied bounded goal-matched evidence. " +
                "Factual truth, source-organization independence, paraphrase agreement, login state, " +
                "hidden page content, provider sharing, memory writes, and autonomous continuation are not inferred."
        )
    }.take(3_200)

    fun continuationSummary(result: BrowserResearchContinuation.Result): String = buildString {
        appendLine("User-selected third public-source comparison complete.")
        appendLine("Source A: " + result.first.finalUrl)
        result.first.excerpts.firstOrNull()?.let { appendLine("A • " + it) }
        appendLine("Source B: " + result.second.finalUrl)
        result.second.excerpts.firstOrNull()?.let { appendLine("B • " + it) }
        appendLine("Source C: " + result.third.finalUrl)
        result.third.excerpts.firstOrNull()?.let { appendLine("C • " + it) }
        appendLine(
            "A↔C relation: " +
                result.firstToThird.claimAssessment.relation.name.lowercase().replace('_', ' ')
        )
        appendLine(
            "B↔C relation: " +
                result.secondToThird.claimAssessment.relation.name.lowercase().replace('_', ' ')
        )
        when (result.disposition) {
            BrowserResearchContinuation.Disposition.BOUNDED_SUMMARY_READY_AFTER_THIRD ->
                appendLine(
                    "Research goal status: bounded three-source summary is ready from strict safe claim support; factual truth and paraphrase equivalence remain unverified."
                )
            BrowserResearchContinuation.Disposition.CONFLICT_REMAINS_AFTER_THIRD ->
                appendLine(
                    "Research goal status: unresolved; a critical-literal conflict remains and no source is selected as correct."
                )
            BrowserResearchContinuation.Disposition.FINAL_UNRESOLVED_NO_ALIGNMENT ->
                appendLine(
                    "Research goal status: unresolved; no safe bounded claim support was established after the bounded third source."
                )
        }
        append(
            "The third source was the final bounded continuation. No fourth source, crawl, login/session reuse, " +
                "provider sharing, memory write, factual-truth claim, or autonomous continuation is authorized."
        )
    }.take(3_200)

    fun executeBlocking(prepared: Prepared): Result {
        val call = WorkspaceAgentReachGitHub.client.newCall(request(prepared))
        return call.execute().use { response ->
            read(prepared, response, System.currentTimeMillis())
        }
    }
}
