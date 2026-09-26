package com.myra.assistant.ui.workspace

/**
 * H19 read-only dependency integrity audit across the verified installed-skill catalog.
 *
 * This never mutates skill state. It reports structural dependency problems so legacy/current
 * catalog state is visible before later actions.
 */
internal object WorkspaceSkillDependencyAudit {
    data class Edge(
        val skillName: String,
        val dependencyName: String,
        val skillState: WorkspaceSkillCatalog.State,
        val dependencyState: WorkspaceSkillCatalog.State?,
    )

    data class Audit(
        val installedSkillCount: Int,
        val declaredDependencyCount: Int,
        val duplicateSkillNames: List<String>,
        val missingDependencies: List<Edge>,
        val disabledDependencyReferences: List<Edge>,
        val enabledDependencyBreaks: List<Edge>,
        val cycles: List<List<String>>,
    ) {
        val healthy: Boolean get() =
            duplicateSkillNames.isEmpty() &&
                missingDependencies.isEmpty() &&
                enabledDependencyBreaks.isEmpty() &&
                cycles.isEmpty()
    }

    fun analyze(installedSkills: Collection<WorkspaceSkillStore.Installed>): Audit {
        installedSkills.forEach {
            WorkspaceSkillEnablement.validateStoredState(it.entry)
            require(
                it.entry.name == it.skill.name &&
                    it.entry.contentSha256 == it.skill.contentSha256 &&
                    it.entry.contentSha256 == it.snapshot.contentSha256 &&
                    it.entry.packageSha256 == it.snapshot.packageSha256
            ) { "Installed dependency audit identity no longer matches its immutable package" }
        }

        val groups = installedSkills.groupBy { it.entry.name }
        val duplicates = groups.filterValues { it.size > 1 }.keys.sorted()
        val unique = groups.filterValues { it.size == 1 }.mapValues { it.value.single() }

        val allEdges = installedSkills
            .flatMap { installed ->
                installed.skill.manifest.dependencySkills.map { dependency ->
                    Edge(
                        skillName = installed.entry.name,
                        dependencyName = dependency,
                        skillState = installed.entry.state,
                        dependencyState = unique[dependency]?.entry?.state,
                    )
                }
            }
            .sortedWith(compareBy<Edge> { it.skillName }.thenBy { it.dependencyName })

        val missing = allEdges.filter { it.dependencyName !in groups }
        val disabledRefs = allEdges.filter {
            it.dependencyState == WorkspaceSkillCatalog.State.INSTALLED_DISABLED
        }
        val enabledBreaks = disabledRefs.filter {
            it.skillState == WorkspaceSkillCatalog.State.ENABLED
        }

        val adjacency = unique.mapValues { (_, installed) ->
            installed.skill.manifest.dependencySkills
                .filter { it in unique }
                .toSortedSet()
        }

        return Audit(
            installedSkillCount = installedSkills.size,
            declaredDependencyCount = allEdges.size,
            duplicateSkillNames = duplicates,
            missingDependencies = missing,
            disabledDependencyReferences = disabledRefs,
            enabledDependencyBreaks = enabledBreaks,
            cycles = stronglyConnectedCycles(adjacency),
        )
    }

    private fun stronglyConnectedCycles(
        adjacency: Map<String, Set<String>>,
    ): List<List<String>> {
        var nextIndex = 0
        val index = mutableMapOf<String, Int>()
        val low = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = mutableSetOf<String>()
        val components = mutableListOf<List<String>>()

        fun visit(node: String) {
            index[node] = nextIndex
            low[node] = nextIndex
            nextIndex += 1
            stack.addLast(node)
            onStack += node

            adjacency[node].orEmpty().forEach { next ->
                if (next !in index) {
                    visit(next)
                    low[node] = minOf(requireNotNull(low[node]), requireNotNull(low[next]))
                } else if (next in onStack) {
                    low[node] = minOf(requireNotNull(low[node]), requireNotNull(index[next]))
                }
            }

            if (low[node] == index[node]) {
                val component = mutableListOf<String>()
                while (true) {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                    if (member == node) break
                }
                val sorted = component.sorted()
                if (sorted.size > 1 ||
                    (sorted.size == 1 && sorted.single() in adjacency[sorted.single()].orEmpty())) {
                    components += sorted
                }
            }
        }

        adjacency.keys.sorted().forEach { node ->
            if (node !in index) visit(node)
        }
        return components.sortedBy { it.joinToString("\u0000") }
    }
}
