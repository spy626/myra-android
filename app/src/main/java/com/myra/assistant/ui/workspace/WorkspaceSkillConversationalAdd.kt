package com.myra.assistant.ui.workspace

/**
 * S2 local conversational Add Skill bridge.
 *
 * This does not introduce a second skill architecture. It projects the existing immutable
 * install/readiness/enable contracts into one consumer approval flow while keeping exact package,
 * permission and environment bindings internal.
 */
internal object WorkspaceSkillConversationalAdd {
    enum class Intent { ADD_REQUEST, CONFIRM, CANCEL, OTHER }

    data class Prepared(
        val skillName: String,
        val description: String,
        val install: WorkspaceSkillInstallApproval.Prepared,
        val enable: WorkspaceSkillEnableApproval.Prepared,
        val userSummary: String,
    )

    data class Applied(
        val installed: WorkspaceSkillStore.Installed,
    )

    private val negativeTokens = setOf(
        "no", "nahi", "nahin", "nhi", "cancel", "mat", "dont", "don't", "stop"
    )
    private val affirmativeTokens = setOf(
        "yes", "haan", "han", "ha", "ok", "okay", "sure", "confirm"
    )
    private val addTokens = setOf(
        "add", "install", "jodo", "jod", "lagao"
    )

    private fun tokens(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("""[^\p{L}\p{N}']+"""), " ")
            .trim()
            .split(Regex("""\s+"""))
            .filter(String::isNotBlank)
            .toSet()

    fun classify(text: String, awaitingConfirmation: Boolean): Intent {
        val words = tokens(text)
        if (words.isEmpty()) return Intent.OTHER
        if (words.any { it in negativeTokens }) return Intent.CANCEL
        if (awaitingConfirmation) {
            if (words.any { it in affirmativeTokens } || words.any { it in addTokens }) {
                return Intent.CONFIRM
            }
            return Intent.OTHER
        }
        return if (words.any { it in addTokens }) Intent.ADD_REQUEST else Intent.OTHER
    }

    private fun provisionalInstalled(
        fresh: WorkspaceSkillInstallApproval.Fresh,
    ): WorkspaceSkillStore.Installed {
        val entry = WorkspaceSkillCatalog.Entry(
            name = fresh.skill.name,
            description = fresh.skill.description,
            contentSha256 = fresh.skill.contentSha256,
            packageSha256 = fresh.snapshot.packageSha256,
            permissionSha256 = fresh.approval.permissionSha256,
            provenance = fresh.skill.provenance,
            installedAtMs = 0L,
            state = WorkspaceSkillCatalog.State.INSTALLED_DISABLED,
        )
        return WorkspaceSkillStore.Installed(entry, fresh.skill, fresh.snapshot)
    }

    private fun candidateEnvironment(
        installed: Collection<WorkspaceSkillStore.Installed>,
        candidateName: String,
    ): WorkspaceSkillEnablement.Environment {
        val base = WorkspaceSkillReadinessSurface.currentEnvironment(installed)
        return base.copy(installedSkills = base.installedSkills + candidateName)
    }

    private fun accessSummary(skill: WorkspaceSkillContract.ParsedSkill): List<String> {
        val p = skill.permissionPreview
        val rows = mutableListOf<String>()
        if (p.tools.isNotEmpty()) rows += "Tools: " + p.tools.joinToString(", ")
        if (p.capabilities.isNotEmpty()) {
            rows += "Capabilities: " + p.capabilities.joinToString(", ")
        }
        if (p.networkDomains.isNotEmpty()) {
            rows += "Network: " + p.networkDomains.joinToString(", ")
        }
        if (p.dependencies.isNotEmpty()) {
            rows += "Dependencies: " + p.dependencies.joinToString(", ")
        }
        if (p.sourceSharing != WorkspaceSkillContract.SourceSharing.NONE) {
            rows += "Source access: " + p.sourceSharing.name
        }
        if (p.memoryAccess != WorkspaceSkillContract.MemoryAccess.NONE) {
            rows += "Memory: " + p.memoryAccess.name
        }
        if (p.modelInvocable) rows += "Model-invocable: Yes"
        if (rows.isEmpty()) {
            rows += "No tools, network, memory, source access, model invocation, or skill dependencies requested."
        }
        return rows
    }

    private fun userSummary(
        skill: WorkspaceSkillContract.ParsedSkill,
        warnings: List<String>,
    ): String = buildString {
        appendLine("Skill: " + skill.name)
        appendLine("What it does: " + skill.description)
        appendLine()
        appendLine("Safety:")
        accessSummary(skill).forEach { appendLine("• " + it) }
        warnings.distinct().forEach { appendLine("• Warning: " + it) }
        appendLine("• Local package, secret, verification, dependency, and readiness checks passed.")
        appendLine()
        appendLine(
            "If you confirm, LYRA will install and enable only this exact reviewed skill. " +
                "If anything changes, it will stop safely."
        )
        appendLine()
        append("Is skill ko add karna hai? Reply “Haan add karo” or “No”.")
    }

    fun prepare(
        skillMdBytes: ByteArray,
        store: WorkspaceSkillStore,
        testedAtMs: Long,
    ): Prepared {
        require(testedAtMs >= 0L) { "Skill review timestamp is invalid" }
        val installed = store.listVerified()
        val install = WorkspaceSkillInstallApproval.prepare(skillMdBytes)
        require(installed.none { it.entry.name == install.skillName }) {
            "A skill named ${install.skillName} is already installed. Use the existing skill instead."
        }
        val fresh = WorkspaceSkillInstallApproval.revalidate(
            prepared = install,
            skillMdBytes = skillMdBytes,
        )
        val provisional = provisionalInstalled(fresh)
        val environment = candidateEnvironment(installed, fresh.skill.name)
        val report = WorkspaceSkillEnablement.test(
            installed = provisional,
            environment = environment,
            testedAtMs = testedAtMs,
        )
        if (report.status != WorkspaceSkillEnablement.Status.PASS ||
            report.checks.any { !it.passed }) {
            val blocked = report.checks.filterNot { it.passed }
                .take(3)
                .joinToString("; ") { it.detail }
            throw IllegalArgumentException(
                "This skill cannot be enabled safely yet" +
                    if (blocked.isBlank()) "." else ": $blocked"
            )
        }
        val enable = WorkspaceSkillEnableApproval.prepare(
            installed = provisional,
            environment = environment,
            report = report,
        )
        return Prepared(
            skillName = fresh.skill.name,
            description = fresh.skill.description,
            install = install,
            enable = enable,
            userSummary = userSummary(fresh.skill, fresh.approval.warnings),
        )
    }

    fun applyApproved(
        prepared: Prepared,
        skillMdBytes: ByteArray,
        store: WorkspaceSkillStore,
        confirmedAtMs: Long,
    ): Applied {
        require(confirmedAtMs >= prepared.enable.request.testedAtMs) {
            "Skill confirmation timestamp is stale"
        }

        // Re-open every current installed package before any mutation. A changed catalog,
        // dependency state or permission surface invalidates the chat approval.
        val before = store.listVerified()
        require(before.none { it.entry.name == prepared.skillName }) {
            "Skill state changed after review; review it again"
        }

        val fresh = WorkspaceSkillInstallApproval.revalidate(
            prepared = prepared.install,
            skillMdBytes = skillMdBytes,
        )
        require(fresh.skill.name == prepared.skillName) {
            "Selected skill changed after review"
        }

        val provisional = provisionalInstalled(fresh)
        val freshEnvironment = candidateEnvironment(before, fresh.skill.name)
        val freshReport = WorkspaceSkillEnablement.test(
            installed = provisional,
            environment = freshEnvironment,
            testedAtMs = prepared.enable.request.testedAtMs,
        )
        val freshEnable = WorkspaceSkillEnableApproval.prepare(
            installed = provisional,
            environment = freshEnvironment,
            report = freshReport,
        )
        require(
            freshEnvironment == prepared.enable.environment &&
                freshEnable.request == prepared.enable.request
        ) {
            "Skill readiness or environment changed after review; review it again"
        }

        val installed = store.installNew(
            skill = fresh.skill,
            packageFiles = fresh.packageFiles,
            approval = fresh.approval,
            approvedToken = fresh.approval.approvalToken,
            installedAtMs = confirmedAtMs,
        )
        require(installed.entry.state == WorkspaceSkillCatalog.State.INSTALLED_DISABLED) {
            "Skill did not remain disabled before final enable verification"
        }

        val actualEnvironment = WorkspaceSkillReadinessSurface.currentEnvironment(
            store.listVerified()
        )
        require(actualEnvironment == prepared.enable.environment) {
            "Skill environment changed during installation; skill remains disabled"
        }
        val enabled = store.enable(
            name = prepared.skillName,
            environment = actualEnvironment,
            request = prepared.enable.request,
            approvedToken = prepared.enable.request.approvalToken,
            enabledAtMs = confirmedAtMs,
        )
        require(enabled.entry.state == WorkspaceSkillCatalog.State.ENABLED) {
            "Skill did not become enabled"
        }
        return Applied(enabled)
    }
}
