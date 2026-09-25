package com.myra.assistant.ui.workspace

/** Local-only receipt for a completed Agent Reach read. No external content is copied into Chat. */
internal object WorkspaceAgentReachReceipt {
    fun github(evidence: WorkspaceAgentReachEvidence.Evidence): String {
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
            appendLine()
            append("I only read public content. Nothing was cloned, installed, executed, " +
                "written to the project, or sent to an AI provider.")
        }
    }
}
