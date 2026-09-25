package com.myra.assistant.ui.workspace

import java.security.MessageDigest

/**
 * Deterministic readiness + approval contract between immutable skill installation and enablement.
 *
 * This never changes catalog/store state and never invokes a model/tool. A skill remains disabled
 * until a later persistence slice receives an exact user-approved EnableRequest token.
 */
internal object WorkspaceSkillEnablement {
    private const val ENABLE_APPROVAL_PREFIX = "lyra-skill-enable-v1:"

    enum class Status { PASS, BLOCKED }

    data class Environment(
        val availableTools: Set<String> = emptySet(),
        val availableCapabilities: Set<String> = emptySet(),
        val installedSkills: Set<String> = emptySet(),
        val boundedSourceGateAvailable: Boolean = false,
        val networkGateAvailable: Boolean = false,
        val memoryReadGateAvailable: Boolean = false,
        val memoryWriteGateAvailable: Boolean = false,
        val modelInvocationGateAvailable: Boolean = false,
    )

    data class Check(
        val id: String,
        val passed: Boolean,
        val detail: String,
    )

    data class ReadinessReport(
        val skillName: String,
        val contentSha256: String,
        val packageSha256: String,
        val permissionSha256: String,
        val environmentSha256: String,
        val testedAtMs: Long,
        val checks: List<Check>,
        val status: Status,
        val reportSha256: String,
    )

    data class EnableRequest(
        val skillName: String,
        val contentSha256: String,
        val packageSha256: String,
        val permissionSha256: String,
        val environmentSha256: String,
        val readinessSha256: String,
        val testedAtMs: Long,
        val approvalToken: String,
        val warnings: List<String>,
    )

    private val sha = Regex("""[0-9a-f]{64}""")
    private val checkId = Regex("""[a-z][a-z0-9-]{0,63}""")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun canonicalSet(values: Set<String>): String =
        values.map(String::trim).filter(String::isNotBlank).sorted().joinToString(",")

    private fun summarize(values: Set<String>): String {
        val sorted = values.map(String::trim).filter(String::isNotBlank).sorted()
        val shown = sorted.take(4).joinToString(",")
        return if (sorted.size <= 4) shown else "$shown (+${sorted.size - 4} more)"
    }

    private fun environmentHash(environment: Environment): String = sha256(buildString {
        appendLine("tools=${canonicalSet(environment.availableTools)}")
        appendLine("capabilities=${canonicalSet(environment.availableCapabilities)}")
        appendLine("skills=${canonicalSet(environment.installedSkills)}")
        appendLine("sourceGate=${environment.boundedSourceGateAvailable}")
        appendLine("networkGate=${environment.networkGateAvailable}")
        appendLine("memoryReadGate=${environment.memoryReadGateAvailable}")
        appendLine("memoryWriteGate=${environment.memoryWriteGateAvailable}")
        append("modelGate=${environment.modelInvocationGateAvailable}")
    })

    private fun check(id: String, passed: Boolean, detail: String): Check {
        require(checkId.matches(id)) { "Skill readiness check id is invalid" }
        require(detail.isNotBlank() && detail.length <= 240 && detail.none(Char::isISOControl)) {
            "Skill readiness check detail is invalid"
        }
        return Check(id, passed, detail)
    }

    private fun reportHash(
        installed: WorkspaceSkillStore.Installed,
        environmentSha256: String,
        testedAtMs: Long,
        checks: List<Check>,
    ): String = sha256(buildString {
        appendLine("name=${installed.entry.name}")
        appendLine("content=${installed.entry.contentSha256}")
        appendLine("package=${installed.entry.packageSha256}")
        appendLine("permissions=${installed.entry.permissionSha256}")
        appendLine("environment=$environmentSha256")
        appendLine("testedAt=$testedAtMs")
        checks.forEach {
            appendLine("check=${it.id}|${it.passed}|${it.detail}")
        }
    })

    fun test(
        installed: WorkspaceSkillStore.Installed,
        environment: Environment,
        testedAtMs: Long,
    ): ReadinessReport {
        require(testedAtMs >= 0L) { "Skill readiness timestamp is invalid" }
        require(installed.entry.state == WorkspaceSkillCatalog.State.INSTALLED_DISABLED) {
            "Only installed-disabled skills enter readiness testing"
        }
        require(installed.entry.name == installed.skill.name &&
            installed.entry.contentSha256 == installed.skill.contentSha256 &&
            installed.entry.contentSha256 == installed.snapshot.contentSha256 &&
            installed.entry.packageSha256 == installed.snapshot.packageSha256) {
            "Installed skill identity no longer matches its immutable package"
        }
        val approval = WorkspaceSkillCatalog.approvalRequest(installed.skill, installed.snapshot)
        require(approval.permissionSha256 == installed.entry.permissionSha256) {
            "Installed skill permissions no longer match the approved package"
        }

        val manifest = installed.skill.manifest
        val missingTools = manifest.allowedTools - environment.availableTools
        val missingCapabilities = manifest.requiredCapabilities - environment.availableCapabilities
        val missingDependencies = manifest.dependencySkills - environment.installedSkills

        val checks = listOf(
            check(
                "immutable-package",
                true,
                "Immutable package, content hash and approved permissions revalidated.",
            ),
            check(
                "verification-gate",
                installed.skill.hasVerificationGate,
                if (installed.skill.hasVerificationGate)
                    "Skill declares an explicit verification or exit-criteria section."
                else "Skill has no explicit verification or exit-criteria section.",
            ),
            check(
                "tools",
                missingTools.isEmpty(),
                if (missingTools.isEmpty()) "All declared tools are available."
                else "Unavailable tools: ${summarize(missingTools)}",
            ),
            check(
                "capabilities",
                missingCapabilities.isEmpty(),
                if (missingCapabilities.isEmpty()) "All declared capabilities are available."
                else "Unavailable capabilities: ${summarize(missingCapabilities)}",
            ),
            check(
                "dependencies",
                missingDependencies.isEmpty(),
                if (missingDependencies.isEmpty()) "All declared skill dependencies are installed."
                else "Missing skill dependencies: ${summarize(missingDependencies)}",
            ),
            check(
                "bounded-source-gate",
                manifest.sourceSharing == WorkspaceSkillContract.SourceSharing.NONE ||
                    environment.boundedSourceGateAvailable,
                if (manifest.sourceSharing == WorkspaceSkillContract.SourceSharing.NONE)
                    "Skill does not request project-source sharing."
                else if (environment.boundedSourceGateAvailable)
                    "Bounded source sharing has an independent per-invocation gate."
                else "Skill requests bounded source sharing but no independent gate is available.",
            ),
            check(
                "network-gate",
                manifest.networkDomains.isEmpty() || environment.networkGateAvailable,
                if (manifest.networkDomains.isEmpty()) "Skill does not request network domains."
                else if (environment.networkGateAvailable)
                    "Declared network domains can be enforced by an exact-domain gate."
                else "Skill requests network access but no exact-domain gate is available.",
            ),
            check(
                "memory-gate",
                when (manifest.memoryAccess) {
                    WorkspaceSkillContract.MemoryAccess.NONE -> true
                    WorkspaceSkillContract.MemoryAccess.READ ->
                        environment.memoryReadGateAvailable
                    WorkspaceSkillContract.MemoryAccess.READ_WRITE ->
                        environment.memoryReadGateAvailable && environment.memoryWriteGateAvailable
                },
                when (manifest.memoryAccess) {
                    WorkspaceSkillContract.MemoryAccess.NONE ->
                        "Skill does not request memory access."
                    WorkspaceSkillContract.MemoryAccess.READ ->
                        if (environment.memoryReadGateAvailable)
                            "Memory read has an independent invocation gate."
                        else "Skill requests memory read but no independent gate is available."
                    WorkspaceSkillContract.MemoryAccess.READ_WRITE ->
                        if (environment.memoryReadGateAvailable &&
                            environment.memoryWriteGateAvailable)
                            "Memory read/write has independent invocation gates."
                        else "Skill requests memory write but required gates are unavailable."
                },
            ),
            check(
                "model-invocation-gate",
                !manifest.modelInvocable || environment.modelInvocationGateAvailable,
                if (!manifest.modelInvocable)
                    "Skill is not model-invocable."
                else if (environment.modelInvocationGateAvailable)
                    "Model invocation is controlled by an independent selection gate."
                else "Skill requests model invocation but no independent selection gate is available.",
            ),
        )
        val envHash = environmentHash(environment)
        val status = if (checks.all { it.passed }) Status.PASS else Status.BLOCKED
        val hash = reportHash(installed, envHash, testedAtMs, checks)
        require(sha.matches(envHash) && sha.matches(hash)) { "Skill readiness hash is invalid" }
        return ReadinessReport(
            skillName = installed.entry.name,
            contentSha256 = installed.entry.contentSha256,
            packageSha256 = installed.entry.packageSha256,
            permissionSha256 = installed.entry.permissionSha256,
            environmentSha256 = envHash,
            testedAtMs = testedAtMs,
            checks = checks,
            status = status,
            reportSha256 = hash,
        )
    }

    fun enableRequest(
        installed: WorkspaceSkillStore.Installed,
        report: ReadinessReport,
    ): EnableRequest {
        require(report.status == Status.PASS && report.checks.all { it.passed }) {
            "Skill readiness must PASS before enablement can be approved"
        }
        require(report.skillName == installed.entry.name &&
            report.contentSha256 == installed.entry.contentSha256 &&
            report.packageSha256 == installed.entry.packageSha256 &&
            report.permissionSha256 == installed.entry.permissionSha256 &&
            sha.matches(report.environmentSha256) &&
            sha.matches(report.reportSha256)) {
            "Skill readiness report does not match the installed immutable package"
        }
        require(report.reportSha256 == reportHash(
            installed, report.environmentSha256, report.testedAtMs, report.checks)) {
            "Skill readiness report hash is stale or tampered"
        }
        val warnings = installed.skill.permissionPreview.warnings.toList()
        val material = buildString {
            appendLine("name=${report.skillName}")
            appendLine("content=${report.contentSha256}")
            appendLine("package=${report.packageSha256}")
            appendLine("permissions=${report.permissionSha256}")
            appendLine("environment=${report.environmentSha256}")
            appendLine("readiness=${report.reportSha256}")
            append("testedAt=${report.testedAtMs}")
        }
        return EnableRequest(
            skillName = report.skillName,
            contentSha256 = report.contentSha256,
            packageSha256 = report.packageSha256,
            permissionSha256 = report.permissionSha256,
            environmentSha256 = report.environmentSha256,
            readinessSha256 = report.reportSha256,
            testedAtMs = report.testedAtMs,
            approvalToken = ENABLE_APPROVAL_PREFIX + sha256(material),
            warnings = warnings,
        )
    }
}
