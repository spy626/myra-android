package com.myra.assistant.ui.workspace

/** Local-only receipt for a completed Agent Reach read. No external content is copied into Chat. */
internal object WorkspaceAgentReachReceipt {
    data class RelevantFile(
        val path: String,
        val reason: String,
        val contentSha256: String,
    )

    private const val PUBLIC_PAGE_PREFIX =
        "Public webpage read complete (static HTML/text only)."

    /** Keep external source text OUT of future provider history unless a separate share is authorized. */
    fun providerSafeHistory(
        messages: List<WorkspaceConversationStore.Message>,
    ): List<WorkspaceConversationStore.Message> = messages.map { message ->
        if (message.role == "assistant" && message.text.startsWith(PUBLIC_PAGE_PREFIX)) {
            message.copy(text = "LYRA read a public URL locally. The source text was NOT shared " +
                "with this model. Do not claim you saw its contents; request separate approval " +
                "before using fetched page evidence.")
        } else message
    }

    /** Local-only preview; static source is NOT automatically sent to a model. */
    fun publicPage(page: WorkspaceAgentReachPublicWeb.Page): String {
        val p = page.evidence.provenance
        require(p.platform != WorkspaceAgentReachPolicy.Platform.GITHUB)
        return buildString {
            appendLine("Public webpage read complete (static HTML/text only).")
            appendLine("Source: " + p.finalUrl)
            appendLine("Fetched (Unix ms): " + p.fetchedAtMs)
            appendLine("Content SHA-256: " + p.contentSha256)
            if (page.title.isNotBlank()) appendLine("Title: " + page.title)
            if (page.headings.isNotEmpty()) {
                appendLine("Headings: " + page.headings.joinToString(" | "))
            }
            if (page.excerpt.isNotBlank()) {
                appendLine()
                appendLine("Readable excerpt (bounded, untrusted source text):")
                appendLine(page.excerpt.take(1_100))
            }
            if (page.suggestedLinks.isNotEmpty()) {
                appendLine()
                appendLine("Same-site links found (not followed):")
                page.suggestedLinks.forEach { appendLine("• " + it) }
            }
            appendLine()
            append("No login, scripts, form actions, installations, browsing clicks or " +
                "AI-provider sharing occurred. This is a bounded page outline, not full " +
                "JavaScript-rendered browser analysis.")
        }
    }

    /**
     * Two locally verified public pages at most; preserve partial initial evidence when
     * a proposed secondary read fails. Follows observed GET links only, not page instructions.
     */
    fun publicJourney(journey: WorkspaceAgentReachWebNavigation.Journey): String {
        val initial = journey.primary
        val p = initial.evidence.provenance
        require(p.platform != WorkspaceAgentReachPolicy.Platform.GITHUB)
        val next = journey.followed
        return buildString {
            appendLine("Public webpage read complete (static HTML/text only).")
            appendLine("Source: " + p.finalUrl)
            appendLine("Fetched (Unix ms): " + p.fetchedAtMs)
            appendLine("Content SHA-256: " + p.contentSha256)
            if (initial.title.isNotBlank()) appendLine("Title: " + initial.title)
            if (initial.headings.isNotEmpty())
                appendLine("Headings: " + initial.headings.joinToString(" | "))
            if (initial.excerpt.isNotBlank()) {
                appendLine()
                appendLine("Initial-page excerpt (untrusted source data):")
                appendLine(initial.excerpt.take(1_000))
            }
            appendLine()
            appendLine("Navigation: " + journey.followUpStatus)
            if (next != null) {
                val fp = next.evidence.provenance
                appendLine("Observed and followed (read-only GET): " + fp.finalUrl)
                appendLine("Follow-up source SHA-256: " + fp.contentSha256)
                if (next.title.isNotBlank()) appendLine("Follow-up title: " + next.title)
                if (next.headings.isNotEmpty())
                    appendLine("Follow-up headings: " + next.headings.joinToString(" | "))
                appendLine("Follow-up excerpt (untrusted source data):")
                appendLine(next.excerpt.take(900))
            }
            if (initial.suggestedLinks.isNotEmpty()) {
                appendLine()
                appendLine("Other observed same-site URLs (not independently verified):")
                initial.suggestedLinks.filterNot { it == next?.evidence?.provenance?.requestedUrl }
                    .take(4).forEach { appendLine("• " + it) }
            }
            appendLine()
            append("Bounded static read only (maximum 2 pages). No login, scripts, " +
                "form action, external-domain crawl, paid browser or AI-provider sharing. " +
                "This is not full site/browser verification.")
        }.take(4_500)
    }

    fun github(
        evidence: WorkspaceAgentReachEvidence.Evidence,
        index: WorkspaceAgentReachGitHub.RepositoryIndex? = null,
        relevantFiles: List<RelevantFile> = emptyList(),
        relevantPathCount: Int? = null,
        relevantError: String? = null,
    ): String {
        val p = evidence.provenance
        require(p.platform == WorkspaceAgentReachPolicy.Platform.GITHUB) {
            "GitHub receipt requires GitHub provenance"
        }
        val revision = p.revision?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Pinned GitHub revision is missing")
        return buildString {
            appendLine("GitHub read complete.")
            appendLine("Pinned revision: ${revision.take(12)}")
            appendLine("Read-only source: ${p.finalUrl}")
            appendLine("Content SHA-256: ${p.contentSha256}")
            index?.let {
                appendLine(
                    "Pinned root index: ${it.entries.size} entries " +
                        "(${it.directories} directories, ${it.files} files)")
                val preview = it.entries.sortedBy { entry -> entry.path.lowercase() }
                    .take(12).joinToString(", ") { entry ->
                    if (entry.kind == WorkspaceAgentReachGitHub.RootEntryKind.DIRECTORY)
                        "${entry.name}/" else entry.name
                }
                if (preview.isNotBlank()) {
                    appendLine(
                        "Root items: $preview" +
                            if (it.entries.size > 12) ", …" else "")
                }
            }
            relevantPathCount?.let { total ->
                appendLine("Relevant-file scan: ${relevantFiles.size} selected from $total indexed paths.")
                relevantFiles.forEach { file ->
                    appendLine("• ${file.path} — ${file.reason}")
                    appendLine("  SHA-256: ${file.contentSha256}")
                }
            }
            relevantError?.takeIf { it.isNotBlank() }?.let {
                appendLine("Relevant-file scan stopped safely: $it")
            }
            appendLine()
            append("I only read public content. Nothing was cloned, installed, executed, " +
                "written to the project, or sent to an AI provider.")
        }
    }
}
