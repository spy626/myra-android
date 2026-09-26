package com.myra.assistant.ui.workspace

/**
 * H18 reverse-dependency guard for skill uninstall.
 *
 * This is deterministic policy only: it never mutates catalog state and never cascades removal or
 * disablement. A target cannot be uninstalled while any other verified installed skill declares it
 * as a dependency; the user must handle those dependent skills explicitly first.
 */
internal object WorkspaceSkillUninstallDependencyGuard {
    data class Dependent(
        val skillName: String,
        val state: WorkspaceSkillCatalog.State,
        val packageSha256: String,
    )

    data class Impact(
        val targetSkillName: String,
        val dependents: List<Dependent>,
    ) {
        val blocked: Boolean get() = dependents.isNotEmpty()
    }

    fun analyze(
        targetSkillName: String,
        installedSkills: Collection<WorkspaceSkillStore.Installed>,
    ): Impact {
        require(targetSkillName.isNotBlank()) { "Uninstall dependency target is invalid" }

        val names = installedSkills.map { it.entry.name }
        require(names.distinct().size == names.size) {
            "Installed skill dependency view contains duplicate names"
        }
        require(names.contains(targetSkillName)) {
            "Uninstall dependency target is not installed"
        }

        val dependents = installedSkills
            .asSequence()
            .filter { it.entry.name != targetSkillName }
            .filter { targetSkillName in it.skill.manifest.dependencySkills }
            .map {
                WorkspaceSkillEnablement.validateStoredState(it.entry)
                require(
                    it.entry.name == it.skill.name &&
                        it.entry.contentSha256 == it.skill.contentSha256 &&
                        it.entry.contentSha256 == it.snapshot.contentSha256 &&
                        it.entry.packageSha256 == it.snapshot.packageSha256
                ) { "Dependent skill identity no longer matches its immutable package" }
                Dependent(
                    skillName = it.entry.name,
                    state = it.entry.state,
                    packageSha256 = it.entry.packageSha256,
                )
            }
            .sortedBy { it.skillName }
            .toList()

        return Impact(targetSkillName, dependents)
    }

    fun requireSafe(impact: Impact) {
        if (!impact.blocked) return
        val shown = impact.dependents.take(4).joinToString(", ") { it.skillName }
        val suffix = if (impact.dependents.size <= 4) ""
            else " (+${impact.dependents.size - 4} more)"
        throw IllegalArgumentException(
            "Cannot uninstall ${impact.targetSkillName} while installed skills depend on it: " +
                shown + suffix
        )
    }
}
