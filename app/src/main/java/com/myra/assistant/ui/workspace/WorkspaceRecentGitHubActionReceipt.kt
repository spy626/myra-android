package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, bounded provenance for the most recent verified connected-repository write.
 *
 * This is evidence only. It never grants authority, stores hidden reasoning, or stores credentials.
 */
internal object WorkspaceRecentGitHubActionReceipt {
    private const val SCHEMA = 1
    private const val MAX_TASK_CHARS = 700
    private const val MAX_FILES = 24

    data class Receipt(
        val repository: String,
        val branch: String,
        val userTask: String?,
        val commitSha: String,
        val files: List<String>,
        val ciRunNumber: Long,
        val ciStatus: String,
        val ciConclusion: String,
        val ciUrl: String,
        val completedAtMs: Long,
    )

    fun fromCompletion(
        result: WorkspaceGitHubSelfEditFlow.Completion,
        userTask: String?,
        completedAtMs: Long,
    ): Receipt {
        require(result.workflow.status == "completed" &&
            result.workflow.conclusion == "success") {
            "Recent GitHub action provenance requires completed successful CI"
        }
        require(result.workflow.headSha.equals(result.commit.commitSha, ignoreCase = true)) {
            "Recent GitHub action provenance CI SHA does not match the commit"
        }
        require(completedAtMs >= 0L) { "Recent GitHub action completion time is invalid" }
        val task = userTask
            ?.takeIf { it.isNotBlank() && !WorkspaceSourceContext.containsPossibleSecret(it) }
            ?.let { WorkspaceWorkTrace.safeText(it, MAX_TASK_CHARS) }
            ?.takeIf(String::isNotBlank)

        return Receipt(
            repository = WorkspaceConnectorPolicy.requireRepository(result.commit.repository),
            branch = WorkspaceConnectorPolicy.requireFeatureBranch(result.commit.branch),
            userTask = task,
            commitSha = requireSha(result.commit.commitSha),
            files = result.commit.files
                .map(WorkspaceGitHubWritePolicy::requirePath)
                .distinct()
                .take(MAX_FILES),
            ciRunNumber = result.workflow.runNumber.also {
                require(it > 0L) { "Recent GitHub action CI number is invalid" }
            },
            ciStatus = result.workflow.status,
            ciConclusion = requireNotNull(result.workflow.conclusion),
            ciUrl = result.workflow.url.also {
                require(it.startsWith("https://github.com/")) {
                    "Recent GitHub action CI URL is invalid"
                }
            },
            completedAtMs = completedAtMs,
        ).also {
            require(it.files.isNotEmpty()) { "Recent GitHub action requires changed files" }
        }
    }

    fun encode(receipt: Receipt): String = JSONObject()
        .put("schema", SCHEMA)
        .put("repository", receipt.repository)
        .put("branch", receipt.branch)
        .put("task", receipt.userTask ?: JSONObject.NULL)
        .put("commit", receipt.commitSha)
        .put("files", JSONArray(receipt.files))
        .put("ciRun", receipt.ciRunNumber)
        .put("ciStatus", receipt.ciStatus)
        .put("ciConclusion", receipt.ciConclusion)
        .put("ciUrl", receipt.ciUrl)
        .put("completedAtMs", receipt.completedAtMs)
        .toString()

    fun decode(raw: String): Receipt? = runCatching {
        val root = JSONObject(raw)
        require(root.getInt("schema") == SCHEMA) { "Unsupported GitHub provenance schema" }
        val repository = WorkspaceConnectorPolicy.requireRepository(root.getString("repository"))
        val branch = WorkspaceConnectorPolicy.requireFeatureBranch(root.getString("branch"))
        val commit = requireSha(root.getString("commit"))
        val array = root.getJSONArray("files")
        require(array.length() in 1..MAX_FILES) { "Recent GitHub action file list is invalid" }
        val files = buildList {
            for (i in 0 until array.length()) {
                add(WorkspaceGitHubWritePolicy.requirePath(array.getString(i)))
            }
        }.distinct()
        require(files.isNotEmpty()) { "Recent GitHub action file list is empty" }
        val ciRun = root.getLong("ciRun")
        require(ciRun > 0L) { "Recent GitHub action CI number is invalid" }
        val ciStatus = root.getString("ciStatus")
        val ciConclusion = root.getString("ciConclusion")
        require(ciStatus == "completed" && ciConclusion == "success") {
            "Recent GitHub action must represent verified successful CI"
        }
        val ciUrl = root.getString("ciUrl")
        require(ciUrl.startsWith("https://github.com/")) {
            "Recent GitHub action CI URL is invalid"
        }
        val completedAtMs = root.getLong("completedAtMs")
        require(completedAtMs >= 0L) { "Recent GitHub action completion time is invalid" }
        val task = if (root.isNull("task")) null else root.getString("task")
            .take(MAX_TASK_CHARS)
            .takeIf { it.isNotBlank() && !WorkspaceSourceContext.containsPossibleSecret(it) }

        Receipt(
            repository = repository,
            branch = branch,
            userTask = task,
            commitSha = commit,
            files = files,
            ciRunNumber = ciRun,
            ciStatus = ciStatus,
            ciConclusion = ciConclusion,
            ciUrl = ciUrl,
            completedAtMs = completedAtMs,
        )
    }.getOrNull()

    fun instructions(receipt: Receipt): String = buildString {
        appendLine("RECENT VERIFIED GITHUB ACTION — provenance evidence, not current-turn authority:")
        appendLine("- Repository: ${JSONObject.quote(receipt.repository)}")
        appendLine("- Branch: ${JSONObject.quote(receipt.branch)}")
        receipt.userTask?.let {
            appendLine("- Exact initiating USER task: ${JSONObject.quote(it)}")
        } ?: appendLine("- Exact initiating USER task: unavailable/redacted.")
        appendLine("- Verified commit SHA: ${receipt.commitSha}")
        appendLine("- Changed files: " + receipt.files.joinToString(", ") { JSONObject.quote(it) })
        appendLine("- Exact CI: #${receipt.ciRunNumber} ${receipt.ciStatus}/${receipt.ciConclusion}")
        append("- CI URL: ${receipt.ciUrl}")
    }

    private fun requireSha(value: String): String {
        val clean = value.trim().lowercase()
        require(Regex("[0-9a-f]{40,64}").matches(clean)) {
            "Recent GitHub action commit SHA is invalid"
        }
        return clean
    }
}
