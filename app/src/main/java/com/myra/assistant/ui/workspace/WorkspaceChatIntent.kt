package com.myra.assistant.ui.workspace

/**
 * Conservative Workspace routing.
 *
 * The current user turn must grant execution authority before Chat can enter a coding
 * lane. Mentions of code/projects, planning, questions, negated work and deferred work
 * remain conversation. This class never infers authority from an older turn.
 */
internal object WorkspaceChatIntent {
    fun requestedProjectType(message: String): WorkspaceProjectType? =
        WorkspaceExecutionAuthority.requestedProjectType(message)

    /** Only for an already selected coding project, never for ordinary/general chats. */
    fun isCodingFollowUp(message: String): Boolean =
        WorkspaceExecutionAuthority.allowsCodingMutation(message)
}
