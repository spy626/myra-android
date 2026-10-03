package com.myra.assistant.ui.workspace

/**
 * Conversational Create Skill bridge.
 *
 * Provider output is only an untrusted draft candidate. Nothing is installed from the model reply
 * directly: the exact generated SKILL.md must pass the same local parser, secret screen,
 * immutable-package approval, readiness and enablement contracts as a user-supplied skill.
 */
internal object WorkspaceSkillConversationalCreate {
    private const val QUESTION_PREFIX = "LYRA_SKILL_QUESTION:"
    private const val DRAFT_BEGIN = "LYRA_SKILL_DRAFT_BEGIN"
    private const val DRAFT_END = "LYRA_SKILL_DRAFT_END"
    private const val FENCE = "\u0060\u0060\u0060"

    const val FIRST_QUESTION =
        "What should the skill do? Describe it in your own words — a short sentence is enough."

    sealed interface ProviderResult {
        data class Question(val text: String) : ProviderResult
        data class Draft(val skillMdBytes: ByteArray) : ProviderResult
    }

    data class Prepared(
        val skillName: String,
        val description: String,
        val skillMdBytes: ByteArray,
        val install: WorkspaceSkillInstallApproval.Prepared,
        val enable: WorkspaceSkillEnableApproval.Prepared,
        val userSummary: String,
    )

    data class Applied(
        val installed: WorkspaceSkillStore.Installed,
    )

    fun systemPrompt(installedSkillNames: Collection<String>): String {
        val installed = installedSkillNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .sorted()
            .joinToString(", ")
            .ifBlank { "none" }
        return """
You are LYRA's Skill Creator. Help the user create one small instruction-only SKILL.md.

Current installed skill names: $installed

If the user's desired behavior is still materially unclear, reply with exactly one short question.
Use this exact prefix, then plain question text with no angle brackets:
$QUESTION_PREFIX What specific detail should the skill handle?

If enough information is available, return exactly one draft between these markers:
$DRAFT_BEGIN
---
name: lowercase-kebab-case-name
description: one concise plain-language sentence
---
# Instructions
Write clear, bounded instructions that implement only the user's requested behavior.

## Verification
State concrete checks the assistant should perform before claiming the skill task is complete.
$DRAFT_END

Rules for a draft:
- Output only SKILL.md, never skill.json and never executable code/scripts.
- Do not request tools, network, memory, project-source access, model invocation, or dependencies.
- Do not include credentials, tokens, passwords, OTPs, private keys, IDs, or secrets.
- Do not copy an installed skill name.
- Keep the body concise and directly tied to the user's request.
- Always include the ## Verification section.
- Do not claim the skill is installed or enabled.
""".trimIndent()
    }

    fun parseProviderReply(raw: String): ProviderResult {
        val reply = raw.trim()
        require(reply.isNotBlank() && reply.length <= WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {
            "Skill Creator returned an empty or oversized reply"
        }

        val questionIndex = reply.indexOf(QUESTION_PREFIX)
        if (questionIndex >= 0) {
            val question = reply.substring(questionIndex + QUESTION_PREFIX.length)
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
                // Some models copy placeholder brackets from formatting examples. They are
                // transport syntax, not user-facing question text.
                .removeSurrounding("<", ">")
                .trim()
            require(question.length in 3..400 && question.none(Char::isISOControl)) {
                "Skill Creator question is invalid"
            }
            return ProviderResult.Question(question)
        }

        val begin = reply.indexOf(DRAFT_BEGIN)
        val end = reply.indexOf(DRAFT_END)
        require(begin >= 0 && end > begin) {
            "Skill Creator did not return a bounded skill draft"
        }
        var draft = reply.substring(begin + DRAFT_BEGIN.length, end).trim()
        if (draft.startsWith(FENCE)) {
            val firstLineEnd = draft.indexOf('\n')
            require(firstLineEnd >= 0) { "Skill Creator draft code fence is invalid" }
            draft = draft.substring(firstLineEnd + 1).trim()
            if (draft.endsWith(FENCE)) draft = draft.dropLast(FENCE.length).trim()
        }
        val bytes = draft.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..WorkspaceSkillImportPreview.MAX_FILE_BYTES) {
            "Generated SKILL.md is empty or exceeds the local skill import limit"
        }
        val parsed = WorkspaceSkillContract.parse(
            skillMd = draft,
            provenance = WorkspaceSkillContract.Provenance(
                WorkspaceSkillContract.Origin.LOCAL_DERIVED
            ),
        )
        require(parsed.hasVerificationGate) {
            "Generated SKILL.md is missing a Verification section"
        }
        require(parsed.manifest.allowedTools.isEmpty() &&
            parsed.manifest.requiredCapabilities.isEmpty() &&
            parsed.manifest.networkDomains.isEmpty() &&
            parsed.manifest.sourceSharing == WorkspaceSkillContract.SourceSharing.NONE &&
            parsed.manifest.memoryAccess == WorkspaceSkillContract.MemoryAccess.NONE &&
            !parsed.manifest.modelInvocable &&
            parsed.manifest.dependencySkills.isEmpty()) {
            "Generated skill requested access that Create Skill does not grant"
        }
        return ProviderResult.Draft(bytes.copyOf())
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

    private fun summary(
        skill: WorkspaceSkillContract.ParsedSkill,
        warnings: List<String>,
    ): String = buildString {
        appendLine("Draft ready ✅")
        appendLine()
        appendLine("Skill: " + skill.name)
        appendLine("What it does: " + skill.description)
        appendLine()
        appendLine("Safety:")
        appendLine("• No tools, network, memory, project-source access, model invocation, or skill dependencies requested.")
        warnings.distinct()
            .filterNot { it.startsWith("Skill is parsed only") }
            .forEach { appendLine("• " + it) }
        appendLine("• Local package, secret, verification, dependency, and readiness checks passed.")
        appendLine()
        appendLine(
            "If you confirm, LYRA will create, install, and enable only this exact reviewed draft. " +
                "If anything changes, it will stop safely."
        )
        appendLine()
        append("Create this skill? Reply “Haan create karo” or “No”.")
    }

    fun prepare(
        skillMdBytes: ByteArray,
        store: WorkspaceSkillStore,
        testedAtMs: Long,
    ): Prepared {
        require(testedAtMs >= 0L) { "Skill review timestamp is invalid" }
        val provenance = WorkspaceSkillContract.Provenance(
            WorkspaceSkillContract.Origin.LOCAL_DERIVED
        )
        val installed = store.listVerified()
        val install = WorkspaceSkillInstallApproval.prepare(
            skillMdBytes = skillMdBytes,
            provenance = provenance,
        )
        require(installed.none { it.entry.name == install.skillName }) {
            "A skill named ${install.skillName} is already installed. Choose a different skill name."
        }
        val fresh = WorkspaceSkillInstallApproval.revalidate(
            prepared = install,
            skillMdBytes = skillMdBytes,
            provenance = provenance,
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
                "This draft cannot be enabled safely yet" +
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
            skillMdBytes = skillMdBytes.copyOf(),
            install = install,
            enable = enable,
            userSummary = summary(fresh.skill, fresh.approval.warnings),
        )
    }

    fun applyApproved(
        prepared: Prepared,
        store: WorkspaceSkillStore,
        confirmedAtMs: Long,
    ): Applied {
        require(confirmedAtMs >= prepared.enable.request.testedAtMs) {
            "Skill confirmation timestamp is stale"
        }
        val before = store.listVerified()
        require(before.none { it.entry.name == prepared.skillName }) {
            "Skill state changed after review; create it again"
        }
        val provenance = WorkspaceSkillContract.Provenance(
            WorkspaceSkillContract.Origin.LOCAL_DERIVED
        )
        val fresh = WorkspaceSkillInstallApproval.revalidate(
            prepared = prepared.install,
            skillMdBytes = prepared.skillMdBytes,
            provenance = provenance,
        )
        require(fresh.skill.name == prepared.skillName) {
            "Generated skill changed after review"
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
            "Skill readiness or environment changed after review; create it again"
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
            "Created skill did not become enabled"
        }
        return Applied(enabled)
    }
}
