package com.myra.assistant.screen

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
        val handoff: BrowserResearchSourceHandoff.Pending,
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
    ): Prepared? {
        if (!destination.publicDnsVerified || destination.permitsNextAction ||
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
        return Prepared(handoff, destination, target)
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
            prepared.handoff.query, journey)
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

    fun executeBlocking(prepared: Prepared): Result {
        val call = WorkspaceAgentReachGitHub.client.newCall(request(prepared))
        return call.execute().use { response ->
            read(prepared, response, System.currentTimeMillis())
        }
    }
}
