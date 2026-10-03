package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject

/**
 * Safe structured outcome for a verified workflow execution.
 *
 * This is experience evidence only. It stores no model prompt, chain-of-thought, credentials,
 * permission grants or source contents, and it never authorizes or triggers a future action.
 */
internal object WorkspaceWorkflowExperience {
    enum class Kind { CONNECTED_GITHUB_SELF_EDIT }
    enum class Outcome { VERIFIED_SUCCESS }

    data class Record(
        val id: String,
        val kind: Kind,
        val userTask: String?,
        val repository: String,
        val branch: String,
        val commitSha: String,
        val changedFiles: List<String>,
        val capabilities: List<String>,
        val evidenceKind: WorkspaceSkillImprovementEvidence.Kind,
        val evidenceSignal: WorkspaceSkillImprovementEvidence.Signal,
        val verificationRef: String,
        val verificationUrl: String,
        val outcome: Outcome,
        val capturedAtMs: Long,
        val providerCalls: Int = 0,
        val reviewCalls: Int = 0,
        val fallbackSwitches: Int = 0,
        val ciRepairs: Int = 0,
        val commitAttempts: Int = 0,
    )

    private val sha = Regex("""[0-9a-f]{40,64}""")
    private val id = Regex("""github:[0-9a-f]{40,64}""")
    private val capability = Regex("""[A-Z][A-Z0-9_]{1,63}""")
    private val verificationRef = Regex("""ci:[1-9][0-9]{0,11}""")

    fun fromVerifiedGitHub(
        receipt: WorkspaceRecentGitHubActionReceipt.Receipt,
        execution: WorkspaceGitHubSelfEditFlow.ExecutionSummary =
            WorkspaceGitHubSelfEditFlow.ExecutionSummary(),
    ): Record {
        require(receipt.ciStatus == "completed" && receipt.ciConclusion == "success") {
            "Workflow experience requires deterministic successful CI verification"
        }
        val task = receipt.userTask
            ?.takeIf { !WorkspaceSourceContext.containsPossibleSecret(it) }
            ?.let { WorkspaceWorkTrace.safeText(it, 700) }
            ?.takeIf(String::isNotBlank)
        return validate(
            Record(
                id = "github:${receipt.commitSha}",
                kind = Kind.CONNECTED_GITHUB_SELF_EDIT,
                userTask = task,
                repository = receipt.repository,
                branch = receipt.branch,
                commitSha = receipt.commitSha,
                changedFiles = receipt.files,
                capabilities = listOf(
                    "CONNECTED_REPOSITORY_READ",
                    "PROTECTED_FEATURE_BRANCH_WRITE",
                    "GITHUB_ACTIONS_CI_VERIFY",
                ),
                evidenceKind = WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION,
                evidenceSignal = WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT,
                verificationRef = "ci:${receipt.ciRunNumber}",
                verificationUrl = receipt.ciUrl,
                outcome = Outcome.VERIFIED_SUCCESS,
                capturedAtMs = receipt.completedAtMs,
                providerCalls = execution.providerCalls,
                reviewCalls = execution.reviewCalls,
                fallbackSwitches = execution.fallbackSwitches,
                ciRepairs = execution.ciRepairs,
                commitAttempts = execution.commitAttempts,
            )
        )
    }

    fun validate(record: Record): Record {
        require(id.matches(record.id)) { "Workflow experience ID is invalid" }
        require(record.kind == Kind.CONNECTED_GITHUB_SELF_EDIT) {
            "Workflow experience kind is unsupported"
        }
        record.userTask?.let {
            require(it.length <= 700 && it.isNotBlank()) { "Workflow experience task is invalid" }
            require(!WorkspaceSourceContext.containsPossibleSecret(it)) {
                "Workflow experience task contains possible secret"
            }
        }
        WorkspaceConnectorPolicy.requireRepository(record.repository)
        WorkspaceConnectorPolicy.requireFeatureBranch(record.branch)
        require(sha.matches(record.commitSha)) { "Workflow experience commit SHA is invalid" }
        require(record.id == "github:${record.commitSha}") {
            "Workflow experience ID does not match commit"
        }
        require(record.changedFiles.isNotEmpty() && record.changedFiles.size <= 24) {
            "Workflow experience changed-file list is invalid"
        }
        require(record.changedFiles.distinct().size == record.changedFiles.size) {
            "Workflow experience changed files must be unique"
        }
        record.changedFiles.forEach(WorkspaceGitHubWritePolicy::requirePath)
        require(record.capabilities.isNotEmpty() && record.capabilities.size <= 12) {
            "Workflow experience capability list is invalid"
        }
        require(record.capabilities.distinct().size == record.capabilities.size &&
            record.capabilities.all(capability::matches)) {
            "Workflow experience capabilities are invalid"
        }
        require(record.evidenceKind ==
            WorkspaceSkillImprovementEvidence.Kind.DETERMINISTIC_VERIFICATION) {
            "Workflow experience evidence must be deterministic verification"
        }
        require(record.evidenceSignal ==
            WorkspaceSkillImprovementEvidence.Signal.SUPPORTS_IMPROVEMENT) {
            "Workflow experience signal is invalid"
        }
        require(verificationRef.matches(record.verificationRef)) {
            "Workflow experience verification ref is invalid"
        }
        require(record.verificationUrl.startsWith("https://github.com/")) {
            "Workflow experience verification URL is invalid"
        }
        require(record.outcome == Outcome.VERIFIED_SUCCESS) {
            "Workflow experience outcome is invalid"
        }
        require(record.capturedAtMs >= 0L) { "Workflow experience timestamp is invalid" }
        require(record.providerCalls in 0..WorkspaceGitHubTaskBudget.MAX_PROVIDER_CALLS) {
            "Workflow experience provider-call count is invalid"
        }
        require(record.reviewCalls in 0..WorkspaceGitHubTaskBudget.MAX_REVIEW_CALLS) {
            "Workflow experience review-call count is invalid"
        }
        require(record.fallbackSwitches in 0..WorkspaceGitHubTaskBudget.MAX_FALLBACK_SWITCHES) {
            "Workflow experience fallback count is invalid"
        }
        require(record.ciRepairs in 0..WorkspaceGitHubTaskBudget.MAX_CI_REPAIRS) {
            "Workflow experience CI-repair count is invalid"
        }
        require(record.commitAttempts in 0..WorkspaceGitHubTaskBudget.MAX_COMMIT_ATTEMPTS) {
            "Workflow experience commit-attempt count is invalid"
        }
        return record
    }

    fun toJson(record: Record): JSONObject {
        val safe = validate(record)
        return JSONObject()
            .put("id", safe.id)
            .put("kind", safe.kind.name)
            .put("userTask", safe.userTask ?: JSONObject.NULL)
            .put("repository", safe.repository)
            .put("branch", safe.branch)
            .put("commitSha", safe.commitSha)
            .put("changedFiles", JSONArray(safe.changedFiles))
            .put("capabilities", JSONArray(safe.capabilities))
            .put("evidenceKind", safe.evidenceKind.name)
            .put("evidenceSignal", safe.evidenceSignal.name)
            .put("verificationRef", safe.verificationRef)
            .put("verificationUrl", safe.verificationUrl)
            .put("outcome", safe.outcome.name)
            .put("capturedAtMs", safe.capturedAtMs)
            .put("providerCalls", safe.providerCalls)
            .put("reviewCalls", safe.reviewCalls)
            .put("fallbackSwitches", safe.fallbackSwitches)
            .put("ciRepairs", safe.ciRepairs)
            .put("commitAttempts", safe.commitAttempts)
    }

    fun fromJson(root: JSONObject): Record? = runCatching {
        fun stringList(key: String, max: Int): List<String> {
            val array = root.getJSONArray(key)
            require(array.length() in 1..max) { "Workflow experience list is invalid" }
            return buildList {
                for (i in 0 until array.length()) add(array.getString(i))
            }
        }
        validate(
            Record(
                id = root.getString("id"),
                kind = Kind.valueOf(root.getString("kind")),
                userTask = if (root.isNull("userTask")) null else root.getString("userTask"),
                repository = root.getString("repository"),
                branch = root.getString("branch"),
                commitSha = root.getString("commitSha"),
                changedFiles = stringList("changedFiles", 24),
                capabilities = stringList("capabilities", 12),
                evidenceKind = WorkspaceSkillImprovementEvidence.Kind.valueOf(
                    root.getString("evidenceKind")),
                evidenceSignal = WorkspaceSkillImprovementEvidence.Signal.valueOf(
                    root.getString("evidenceSignal")),
                verificationRef = root.getString("verificationRef"),
                verificationUrl = root.getString("verificationUrl"),
                outcome = Outcome.valueOf(root.getString("outcome")),
                capturedAtMs = root.getLong("capturedAtMs"),
                providerCalls = root.optInt("providerCalls", 0),
                reviewCalls = root.optInt("reviewCalls", 0),
                fallbackSwitches = root.optInt("fallbackSwitches", 0),
                ciRepairs = root.optInt("ciRepairs", 0),
                commitAttempts = root.optInt("commitAttempts", 0),
            )
        )
    }.getOrNull()
}
