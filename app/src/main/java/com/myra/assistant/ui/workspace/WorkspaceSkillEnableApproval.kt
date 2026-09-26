package com.myra.assistant.ui.workspace

/**
 * H4 human-facing approval projection for one fresh PASS readiness report.
 *
 * The approval token is never rendered. It stays internal and is accepted only after the Settings
 * UI receives an explicit positive button tap for the exact summary derived here.
 */
internal object WorkspaceSkillEnableApproval {
    data class Prepared(
        val skillName: String,
        val environment: WorkspaceSkillEnablement.Environment,
        val request: WorkspaceSkillEnablement.EnableRequest,
        val approvalSummary: String,
    )

    private val sha = Regex("""[0-9a-f]{64}""")

    private fun short(value: String): String {
        require(sha.matches(value)) { "Skill enable fingerprint is invalid" }
        return value.take(12)
    }

    fun prepare(
        installed: WorkspaceSkillStore.Installed,
        environment: WorkspaceSkillEnablement.Environment,
        report: WorkspaceSkillEnablement.ReadinessReport,
    ): Prepared {
        require(report.status == WorkspaceSkillEnablement.Status.PASS &&
            report.checks.all { it.passed }) {
            "Blocked readiness cannot produce an enable approval"
        }
        val request = WorkspaceSkillEnablement.enableRequest(installed, report)
        val p = installed.skill.permissionPreview
        val warnings = request.warnings
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n") { "• " + it }
            ?: "None"

        val summary = buildString {
            appendLine("Enable only this exact verified skill package:")
            appendLine()
            appendLine("Skill: " + request.skillName)
            appendLine("Content: " + short(request.contentSha256))
            appendLine("Package: " + short(request.packageSha256))
            appendLine("Permissions: " + short(request.permissionSha256))
            appendLine("Environment: " + short(request.environmentSha256))
            appendLine("Readiness: " + short(request.readinessSha256))
            appendLine()
            appendLine(
                "Declared access: tools " + p.tools.size +
                    " · capabilities " + p.capabilities.size +
                    " · network " + p.networkDomains.size +
                    " · dependencies " + p.dependencies.size
            )
            appendLine(
                "Source " + p.sourceSharing.name +
                    " · memory " + p.memoryAccess.name +
                    " · model-invocable " + p.modelInvocable
            )
            appendLine()
            appendLine("Warnings:")
            appendLine(warnings)
            appendLine()
            append(
                "Enabling does not create new permissions. Every later invocation still passes " +
                    "its own current authority and verification gates."
            )
        }
        require(summary.length <= 4_000 && summary.none { it == '\u0000' }) {
            "Skill enable approval summary is invalid"
        }
        return Prepared(
            skillName = request.skillName,
            environment = environment,
            request = request,
            approvalSummary = summary,
        )
    }
}
