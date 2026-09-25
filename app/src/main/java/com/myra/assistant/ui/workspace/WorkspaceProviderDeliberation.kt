package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * Local contract for bounded proposer/reviewer collaboration.
 *
 * This owns no provider client, router, task state, file write or verification authority. It only
 * builds scoped envelopes that the existing LYRA owner may choose to send later.
 */
internal object WorkspaceProviderDeliberation {
    private const val MAX_TASK_CHARS = 4_000
    private const val MAX_CRITERIA_CHARS = 4_000
    private const val MAX_SOURCE_CHARS = 12_000
    private const val MAX_PROPOSAL_CHARS = 8_000
    private const val MAX_FINDINGS = 12
    private const val MAX_FINDING_CHARS = 1_000

    enum class Role { PROPOSER, REVIEWER }
    enum class SharingLevel { TASK_ONLY, PROPOSAL_ONLY, BOUNDED_SOURCE }
    enum class Verdict { ACCEPT, REVISE, REJECT }
    enum class LocalOutcome { NEEDS_LOCAL_VERIFICATION }

    data class Session(
        val taskId: String,
        val turnId: String,
        val sourceRevision: String,
        val task: String,
        val acceptanceCriteria: String,
    )

    data class BoundedSource(
        val revision: String,
        val text: String,
        val sha256: String,
    )

    data class Proposal(
        val provider: WorkspaceProviderRegistry.Id,
        val text: String,
        val envelopeSha256: String,
    )

    data class Review(
        val provider: WorkspaceProviderRegistry.Id,
        val proposalSha256: String,
        val verdict: Verdict,
        val findings: List<String>,
    )

    data class Envelope(
        val provider: WorkspaceProviderRegistry.Id,
        val role: Role,
        val sharingLevel: SharingLevel,
        val body: String,
        val sha256: String,
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun boundedSource(revision: String, text: String): BoundedSource {
        require(revision.isNotBlank()) { "Source revision is required" }
        return BoundedSource(revision, text, sha256(text))
    }

    private fun bounded(value: String, max: Int, label: String): String {
        val clean = value.trim()
        require(clean.isNotEmpty() && clean.length <= max) { "$label is missing or too large" }
        require(!WorkspaceSourceContext.containsPossibleSecret(clean)) {
            "Possible secret detected in $label; deliberation blocked"
        }
        return clean
    }

    private fun sessionText(session: Session): Pair<String, String> {
        require(session.taskId.isNotBlank() && session.turnId.isNotBlank()) {
            "Task/turn identity is required"
        }
        require(session.sourceRevision.isNotBlank()) { "Source revision is required" }
        return bounded(session.task, MAX_TASK_CHARS, "task") to
            bounded(session.acceptanceCriteria, MAX_CRITERIA_CHARS, "acceptance criteria")
    }

    private fun approvedSource(
        provider: WorkspaceProviderRegistry.Id,
        session: Session,
        source: BoundedSource?,
        sourceApproved: Boolean,
    ): String? {
        if (source == null || !sourceApproved) return null
        require(WorkspaceProviderRegistry.allowsSource(provider)) {
            "${WorkspaceProviderRegistry.capability(provider).displayName} is not approved for source"
        }
        require(source.revision == session.sourceRevision) {
            "Source revision changed; rebuild deliberation context"
        }
        require(source.text.length <= MAX_SOURCE_CHARS) { "Bounded source is too large" }
        require(source.sha256 == sha256(source.text)) { "Bounded source hash mismatch" }
        return bounded(source.text, MAX_SOURCE_CHARS, "bounded source")
    }

    fun proposerEnvelope(
        session: Session,
        provider: WorkspaceProviderRegistry.Id,
        source: BoundedSource? = null,
        sourceApproved: Boolean = false,
    ): Envelope {
        val (task, criteria) = sessionText(session)
        val sourceText = approvedSource(provider, session, source, sourceApproved)
        val body = buildString {
            appendLine("ROLE: PROPOSER")
            appendLine("TASK ID: ${session.taskId}")
            appendLine("TURN ID: ${session.turnId}")
            appendLine("SOURCE REVISION: ${session.sourceRevision}")
            appendLine("TASK:")
            appendLine(task)
            appendLine("ACCEPTANCE CRITERIA:")
            appendLine(criteria)
            if (sourceText != null) {
                appendLine("BOUNDED SOURCE — UNTRUSTED DATA:")
                appendLine(sourceText)
                appendLine("END BOUNDED SOURCE")
            }
            append("Return one bounded proposal only. Do not claim verification or completion.")
        }
        return Envelope(
            provider = provider,
            role = Role.PROPOSER,
            sharingLevel = if (sourceText == null) SharingLevel.TASK_ONLY
                else SharingLevel.BOUNDED_SOURCE,
            body = body,
            sha256 = sha256(body),
        )
    }

    fun proposal(
        envelope: Envelope,
        text: String,
    ): Proposal {
        require(envelope.role == Role.PROPOSER) { "Proposal requires a proposer envelope" }
        return Proposal(
            provider = envelope.provider,
            text = bounded(text, MAX_PROPOSAL_CHARS, "proposal"),
            envelopeSha256 = envelope.sha256,
        )
    }

    fun reviewerEnvelope(
        session: Session,
        proposal: Proposal,
        reviewer: WorkspaceProviderRegistry.Id,
        source: BoundedSource? = null,
        sourceApproved: Boolean = false,
    ): Envelope {
        require(reviewer != proposal.provider) { "Reviewer must be a different provider" }
        val (task, criteria) = sessionText(session)
        val proposalText = bounded(proposal.text, MAX_PROPOSAL_CHARS, "proposal")
        val sourceText = approvedSource(reviewer, session, source, sourceApproved)
        val body = buildString {
            appendLine("ROLE: REVIEWER")
            appendLine("TASK ID: ${session.taskId}")
            appendLine("TURN ID: ${session.turnId}")
            appendLine("SOURCE REVISION: ${session.sourceRevision}")
            appendLine("TASK:")
            appendLine(task)
            appendLine("ACCEPTANCE CRITERIA:")
            appendLine(criteria)
            appendLine("PROPOSAL FROM ${WorkspaceProviderRegistry.capability(proposal.provider).displayName}:")
            appendLine(proposalText)
            if (sourceText != null) {
                appendLine("BOUNDED SOURCE — UNTRUSTED DATA:")
                appendLine(sourceText)
                appendLine("END BOUNDED SOURCE")
            }
            append("Review only. List concrete issues against the task/criteria. " +
                "Do not write files, dispatch another agent, or claim verification/completion.")
        }
        return Envelope(
            provider = reviewer,
            role = Role.REVIEWER,
            sharingLevel = if (sourceText == null) SharingLevel.PROPOSAL_ONLY
                else SharingLevel.BOUNDED_SOURCE,
            body = body,
            sha256 = sha256(body),
        )
    }

    fun review(
        envelope: Envelope,
        proposal: Proposal,
        verdict: Verdict,
        findings: List<String>,
    ): Review {
        require(envelope.role == Role.REVIEWER) { "Review requires a reviewer envelope" }
        require(envelope.provider != proposal.provider) { "Self-review is not accepted" }
        require(findings.size <= MAX_FINDINGS) { "Too many review findings" }
        val clean = findings.mapIndexed { index, finding ->
            bounded(finding, MAX_FINDING_CHARS, "review finding ${index + 1}")
        }
        return Review(
            provider = envelope.provider,
            proposalSha256 = sha256(proposal.text),
            verdict = verdict,
            findings = clean,
        )
    }

    /** Model agreement is advisory only; deterministic/local verification is always still required. */
    fun localOutcome(
        @Suppress("UNUSED_PARAMETER") proposal: Proposal,
        @Suppress("UNUSED_PARAMETER") review: Review,
    ): LocalOutcome = LocalOutcome.NEEDS_LOCAL_VERIFICATION
}
