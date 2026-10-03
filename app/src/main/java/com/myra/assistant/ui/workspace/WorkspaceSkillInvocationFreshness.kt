package com.myra.assistant.ui.workspace

/**
 * H23 completion-time freshness gate for one prepared skill invocation.
 *
 * Provider output has no authority to revive a stale skill turn. Before a reply is attached/saved,
 * the exact skill package, permission identity and activation binding must still match, and every
 * declared dependency must still be currently enabled.
 */
internal object WorkspaceSkillInvocationFreshness {
    fun requireCurrent(
        store: WorkspaceSkillStore,
        projection: WorkspaceSkillInvocation.Projection,
    ): WorkspaceSkillStore.Installed {
        val current = store.load(projection.skillName)
        WorkspaceSkillEnablement.validateStoredState(current.entry)

        require(current.entry.state == WorkspaceSkillCatalog.State.ENABLED) {
            "Skill invocation became stale because the skill is no longer enabled"
        }
        require(
            current.entry.contentSha256 == projection.contentSha256 &&
                current.entry.packageSha256 == projection.packageSha256 &&
                current.entry.permissionSha256 == projection.permissionSha256
        ) {
            "Skill invocation became stale because the installed package changed"
        }
        require(
            current.entry.enableBindingSha256 == projection.activationBindingSha256
        ) {
            "Skill invocation became stale because activation authority changed"
        }

        val installed = store.listVerified()
        val enabledNames = installed
            .filter { it.entry.state == WorkspaceSkillCatalog.State.ENABLED }
            .map { it.entry.name }
            .toSet()
        val missing = current.skill.manifest.dependencySkills - enabledNames
        require(missing.isEmpty()) {
            "Skill invocation became stale because dependencies are no longer enabled: " +
                missing.sorted().joinToString(",")
        }

        return current
    }
}
