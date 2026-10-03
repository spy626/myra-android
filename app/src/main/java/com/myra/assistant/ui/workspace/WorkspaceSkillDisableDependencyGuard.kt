package com.myra.assistant.ui.workspace

/**
 * H21 reverse-dependency guard for skill disable.
 *
 * Only ENABLED dependents block disabling a dependency. Disabled dependents remain installed but hold
 * no runtime activation authority. This policy never cascades disablement and never mutates state.
 */
internal object WorkspaceSkillDisableDependencyGuard {
    enum class Operation(val verb: String) {
        DISABLE("disable"),
        UPDATE("update"),
        ROLLBACK("roll back"),
    }

    data class Dependent(
        val skillName: String,
        val packageSha256: String,
        val activationBindingSha256: String,
    )

    data class Impact(
        val targetSkillName: String,
        val enabledDependents: List<Dependent>,
    ) {
        val blocked: Boolean get() = enabledDependents.isNotEmpty()
    }

    fun analyze(
        targetSkillName: String,
        installedSkills: Collection<WorkspaceSkillStore.Installed>,
    ): Impact {
        require(targetSkillName.isNotBlank()) { "Disable dependency target is invalid" }

        val groups = installedSkills.groupBy { it.entry.name }
        require(groups.values.all { it.size == 1 }) {
            "Installed skill dependency view contains duplicate names"
        }
        require(targetSkillName in groups) {
            "Disable dependency target is not installed"
        }

        val dependents = installedSkills
            .asSequence()
            .filter { it.entry.name != targetSkillName }
            .filter { it.entry.state == WorkspaceSkillCatalog.State.ENABLED }
            .filter { targetSkillName in it.skill.manifest.dependencySkills }
            .map {
                WorkspaceSkillEnablement.validateStoredState(it.entry)
                require(
                    it.entry.name == it.skill.name &&
                        it.entry.contentSha256 == it.skill.contentSha256 &&
                        it.entry.contentSha256 == it.snapshot.contentSha256 &&
                        it.entry.packageSha256 == it.snapshot.packageSha256
                ) { "Enabled dependent skill identity no longer matches its immutable package" }
                Dependent(
                    skillName = it.entry.name,
                    packageSha256 = it.entry.packageSha256,
                    activationBindingSha256 =
                        requireNotNull(it.entry.enableBindingSha256) {
                            "Enabled dependent is missing activation binding"
                        },
                )
            }
            .sortedBy { it.skillName }
            .toList()

        return Impact(
            targetSkillName = targetSkillName,
            enabledDependents = dependents,
        )
    }

    fun requireSafe(impact: Impact) =
        requireSafe(impact, Operation.DISABLE)

    fun requireSafe(
        impact: Impact,
        operation: Operation,
    ) {
        if (!impact.blocked) return
        val shown = impact.enabledDependents.take(4).joinToString(", ") { it.skillName }
        val suffix = if (impact.enabledDependents.size <= 4) ""
            else " (+${impact.enabledDependents.size - 4} more)"
        throw IllegalArgumentException(
            "Cannot ${operation.verb} ${impact.targetSkillName} while enabled skills depend on it: " +
                shown + suffix
        )
    }
}
