package com.myra.assistant.ui.workspace

/**
 * H3 read-only readiness projection.
 *
 * Current Phase-1 explicit skill invocation has no per-invocation grants for tools, capabilities,
 * network, project source, memory or model selection. This surface mirrors that reality instead of
 * inventing availability. Installed skill names are the only dependency information projected.
 */
internal object WorkspaceSkillReadinessSurface {
    data class Row(
        val id: String,
        val passed: Boolean,
        val detail: String,
    )

    data class View(
        val status: WorkspaceSkillEnablement.Status,
        val environmentSha256: String,
        val reportSha256: String,
        val testedAtMs: Long,
        val rows: List<Row>,
    )

    fun currentEnvironment(
        installedSkills: Collection<WorkspaceSkillStore.Installed>,
    ): WorkspaceSkillEnablement.Environment =
        WorkspaceSkillEnablement.Environment(
            installedSkills = installedSkills.map { it.entry.name }.toSet(),
        )

    fun view(report: WorkspaceSkillEnablement.ReadinessReport): View =
        View(
            status = report.status,
            environmentSha256 = report.environmentSha256,
            reportSha256 = report.reportSha256,
            testedAtMs = report.testedAtMs,
            rows = report.checks.map { Row(it.id, it.passed, it.detail) },
        )
}
