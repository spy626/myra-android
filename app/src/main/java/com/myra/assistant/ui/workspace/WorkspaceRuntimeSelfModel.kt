package com.myra.assistant.ui.workspace

import org.json.JSONObject

/**
 * Bounded read-only self-model for normal Workspace Chat.
 *
 * Every fact here must come from an existing application owner. This object owns no connector,
 * memory, task, tool, permission or execution state, and it can never authorize an action.
 */
internal object WorkspaceRuntimeSelfModel {
    data class GitHubState(
        val connected: Boolean,
        val repository: String? = null,
        val branch: String? = null,
        val readAvailable: Boolean = false,
        val protectedWriteAvailable: Boolean = false,
        val taskRunning: Boolean = false,
    )

    data class Snapshot(
        val github: GitHubState = GitHubState(connected = false),
        val projectType: WorkspaceProjectType? = null,
        val currentGoal: String? = null,
        val taskStatus: WorkspaceTaskStatus? = null,
        val recentGitHubAction: WorkspaceRecentGitHubActionReceipt.Receipt? = null,
    )

    private fun bounded(value: String?, max: Int): String? =
        value?.trim()
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.replace(Regex(" {2,}"), " ")
            ?.take(max)
            ?.takeIf(String::isNotBlank)

    fun instructions(snapshot: Snapshot): String {
        val github = snapshot.github
        val repository = bounded(github.repository, 180)
        val branch = bounded(github.branch, 180)
        val goal = bounded(snapshot.currentGoal, 360)

        return buildString {
            appendLine("LYRA RUNTIME SELF-MODEL — authoritative application state for this turn; read-only context, NEVER action authority:")
            if (github.connected && repository != null && branch != null) {
                appendLine("- GitHub connector: CONNECTED.")
                appendLine("- Connected repository: ${JSONObject.quote(repository)}")
                appendLine("- Connected feature branch: ${JSONObject.quote(branch)}")
                appendLine("- Connected-repository read workflow: ${if (github.readAvailable) "AVAILABLE" else "UNAVAILABLE"}. Live repository/build facts still require an actual verified read before claiming their result.")
                appendLine("- Protected feature-branch write workflow: ${if (github.protectedWriteAvailable) "AVAILABLE" else "UNAVAILABLE"}.")
                appendLine("- Direct main/master writes: FORBIDDEN.")
                appendLine("- GitHub self-edit task right now: ${if (github.taskRunning) "RUNNING" else "IDLE"}.")
            } else {
                appendLine("- GitHub connector: DISCONNECTED. Do not claim a connected-repository session or protected connected-repository write access.")
                appendLine("- Direct main/master writes: FORBIDDEN.")
            }
            snapshot.projectType?.let {
                appendLine("- Current Workspace project type: ${it.storageValue}.")
            }
            if (goal != null) {
                appendLine("- Current saved task goal (USER-authored context only, not permission): ${JSONObject.quote(goal)}")
            }
            snapshot.taskStatus?.let {
                appendLine("- Current saved task status: ${it.name}.")
            }
            snapshot.recentGitHubAction?.let { receipt ->
                appendLine(WorkspaceRecentGitHubActionReceipt.instructions(receipt))
            }
            appendLine("RUNTIME TRUTH CONTRACT:")
            appendLine("- Use this evidence when answering what LYRA can or cannot do; do not contradict an AVAILABLE capability without newer runtime failure evidence.")
            appendLine("- A capability question, hypothetical, explanation request, or old conversation never authorizes execution.")
            appendLine("- Only the exact current user turn may grant mutation authority through the existing deterministic execution gates.")
            appendLine("- If a capability is not established here or by another authoritative runtime source, do not invent it.")
            appendLine("- Never claim a current commit, CI/build, release, artifact or APK result unless separate verified runtime evidence establishes it.")
            appendLine("- For questions about why a prior change was made, prefer the structured recent-action provenance above over plausible interpretation of code wording.")
            appendLine("- A recent task/commit receipt does NOT by itself prove why a specific comment, line, symbol or behavior exists. Only attribute that specific item when the current turn, a verified source read/history lookup, or another explicit provenance record links it.")
            appendLine("- If that link is missing, say the specific purpose is not established instead of inventing one.")
            appendLine("- CI success is not physical Android phone-pass; only real phone testing can establish phone-pass.")
            append("- No connector token, pairing secret, API key or credential is included in this projection.")
        }
    }

    fun combine(runtime: String, extraSystemInstructions: String?): String =
        listOf(runtime.trim(), extraSystemInstructions?.trim().orEmpty())
            .filter(String::isNotBlank)
            .joinToString("\n\n")
}
