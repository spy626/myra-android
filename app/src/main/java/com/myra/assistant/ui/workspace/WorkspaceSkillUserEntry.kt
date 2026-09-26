package com.myra.assistant.ui.workspace

/**
 * Freshly binds an explicit /skill turn to the current immutable enabled package.
 *
 * Phase 1 grants only already-enabled dependency skills. Tools, network, source and memory remain
 * denied until separate per-invocation approval UI exists. Model-selected invocation is not used.
 */
internal object WorkspaceSkillUserEntry {
    data class Prepared(
        val command: WorkspaceSkillUserCommand.Parsed,
        val projection: WorkspaceSkillInvocation.Projection,
    )

    fun prepare(
        store: WorkspaceSkillStore,
        projectId: String,
        turnId: String,
        message: String,
    ): Prepared? {
        val command = WorkspaceSkillUserCommand.parse(message) ?: return null
        val installed = store.load(command.skillName)
        val enabledSkills = store.listVerified()
            .filter { it.entry.state == WorkspaceSkillCatalog.State.ENABLED }
            .map { it.entry.name }
            .toSet()
        val projection = WorkspaceSkillInvocation.prepare(
            installed,
            WorkspaceSkillInvocation.Context(
                invocationId = "skill:$turnId",
                taskId = projectId,
                turnId = turnId,
                origin = WorkspaceSkillInvocation.Origin.USER_EXPLICIT,
                grants = WorkspaceSkillInvocation.Grants(
                    enabledSkills = enabledSkills,
                    modelSelectionApproved = false,
                ),
            ),
        )
        require(projection.origin == WorkspaceSkillInvocation.Origin.USER_EXPLICIT &&
            projection.turnId == turnId && projection.taskId == projectId) {
            "Skill invocation binding changed unexpectedly"
        }
        return Prepared(command, projection)
    }
}
