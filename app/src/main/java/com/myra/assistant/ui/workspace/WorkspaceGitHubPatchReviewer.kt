package com.myra.assistant.ui.workspace

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Read-only second-provider review contract.
 *
 * The reviewer cannot mutate files or grant authority. It sees only a bounded description of a
 * locally validated proposed patch and returns one strict decision.
 */
internal object WorkspaceGitHubPatchReviewer {
    enum class Decision { ACCEPT, REVISE, REJECT }

    data class Review(
        val decision: Decision,
        val summary: String,
        val risks: List<String>,
    )

    private const val MAX_PROMPT_CHARS = 18_000
    private const val MAX_RESPONSE_CHARS = 5_000
    private const val MAX_SUMMARY_CHARS = 700
    private const val MAX_RISKS = 5
    private const val MAX_RISK_CHARS = 240
    private const val MAX_WINDOW_CHARS = 2_200

    fun prompt(
        goal: String,
        plan: WorkspaceGitHubCodingPlan.Plan,
        originals: Map<String, String>,
        prepared: WorkspaceGitHubSelfEditBatch.Prepared,
    ): String {
        require(goal.trim() == plan.goal) { "Reviewer goal does not match the locked coding plan" }
        require(prepared.files.isNotEmpty() && prepared.files.size <= WorkspaceGitHubSelfEditBatch.MAX_FILES) {
            "Reviewer patch file count is outside the LYRA bound"
        }
        val originalByPath = originals.mapKeys { WorkspaceGitHubWritePolicy.requirePath(it.key) }
        val body = buildString {
            appendLine("You are a READ-ONLY QA reviewer for a bounded LYRA GitHub coding task.")
            appendLine("Do not propose code, patches, tools, commits, merges, or follow-up actions.")
            appendLine("Return exactly ONE JSON object and nothing else.")
            appendLine("Keys only: schemaVersion, decision, summary, risks.")
            appendLine("schemaVersion must be 1.")
            appendLine("decision must be exactly ACCEPT, REVISE, or REJECT.")
            appendLine("ACCEPT only if the proposed change is consistent with the user goal and selected scope.")
            appendLine("REVISE for a concrete fixable correctness/regression/compile concern.")
            appendLine("REJECT for scope drift, unsafe behavior, or a change that should not be committed.")
            appendLine("Do not claim CI/build success; exact GitHub Actions verification happens later.")
            appendLine("LOCKED GOAL: ${JSONObject.quote(plan.goal)}")
            appendLine("EXPECTED CHANGE: ${JSONObject.quote(plan.expectedChange)}")
            appendLine("SELECTED PATHS: ${JSONObject.quote(plan.selectedPaths.joinToString(", "))}")
            prepared.files.forEach { change ->
                val original = originalByPath[change.path]
                    ?: throw IllegalArgumentException("Reviewer patch targeted an unselected source file")
                require(plan.selectedPaths.any { it.equals(change.path, ignoreCase = true) }) {
                    "Reviewer patch targeted a path outside the locked plan"
                }
                val window = changedWindow(original, change.content)
                appendLine("PROPOSED FILE: ${JSONObject.quote(change.path)}")
                appendLine("BEFORE WINDOW:")
                appendLine(window.first)
                appendLine("END BEFORE WINDOW")
                appendLine("AFTER WINDOW:")
                appendLine(window.second)
                appendLine("END AFTER WINDOW")
            }
        }
        require(body.length <= MAX_PROMPT_CHARS) { "Reviewer prompt exceeds the bounded context limit" }
        require(!WorkspaceSourceContext.containsPossibleSecret(body)) {
            "Possible secret detected in reviewer context; review was not sent"
        }
        return body
    }

    private fun changedWindow(before: String, after: String): Pair<String, String> {
        var prefix = 0
        val prefixLimit = minOf(before.length, after.length)
        while (prefix < prefixLimit && before[prefix] == after[prefix]) prefix += 1

        var suffix = 0
        val beforeRemaining = before.length - prefix
        val afterRemaining = after.length - prefix
        val suffixLimit = minOf(beforeRemaining, afterRemaining)
        while (suffix < suffixLimit &&
            before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) {
            suffix += 1
        }

        val context = 350
        val beforeStart = (prefix - context).coerceAtLeast(0)
        val afterStart = (prefix - context).coerceAtLeast(0)
        val beforeEnd = (before.length - suffix + context).coerceAtMost(before.length)
        val afterEnd = (after.length - suffix + context).coerceAtMost(after.length)
        return bounded(before.substring(beforeStart, beforeEnd)) to
            bounded(after.substring(afterStart, afterEnd))
    }

    private fun bounded(value: String): String {
        if (value.length <= MAX_WINDOW_CHARS) return value
        val half = MAX_WINDOW_CHARS / 2
        return value.take(half) + "\n...[review window clipped]...\n" + value.takeLast(half)
    }

    fun read(rawJson: String): Review {
        require(rawJson.length in 1..MAX_RESPONSE_CHARS) {
            "Reviewer response is empty or oversized"
        }
        val root = runCatching {
            val tokener = JSONTokener(rawJson)
            val value = tokener.nextValue()
            require(value is JSONObject && tokener.nextClean() == '\u0000') {
                "Reviewer response must be exactly one JSON object"
            }
            value
        }.getOrElse {
            if (it is IllegalArgumentException) throw it
            throw IllegalArgumentException("Reviewer response is invalid JSON")
        }
        require(root.keys().asSequence().toSet() ==
            setOf("schemaVersion", "decision", "summary", "risks")) {
            "Reviewer JSON contains missing or unsupported fields"
        }
        require(root.optInt("schemaVersion", -1) == 1) { "Reviewer schema version is unsupported" }
        val decision = runCatching {
            Decision.valueOf(root.getString("decision").trim())
        }.getOrElse { throw IllegalArgumentException("Reviewer decision is unsupported") }
        val summary = root.getString("summary").trim()
        require(summary.length in 1..MAX_SUMMARY_CHARS && summary.none(Char::isISOControl)) {
            "Reviewer summary is invalid"
        }
        val risks = when (val rawRisks = root.get("risks")) {
            is JSONArray -> {
                require(rawRisks.length() <= MAX_RISKS) { "Reviewer returned too many risks" }
                buildList {
                    for (index in 0 until rawRisks.length()) {
                        val value = rawRisks.opt(index)
                        require(value is String) { "Reviewer risk must be text" }
                        val risk = value.trim()
                        require(risk.length in 1..MAX_RISK_CHARS &&
                            risk.none(Char::isISOControl)) {
                            "Reviewer risk text is invalid"
                        }
                        add(risk)
                    }
                }
            }
            is String -> {
                val risk = rawRisks.trim()
                require(risk.length in 1..MAX_RISK_CHARS &&
                    risk.none(Char::isISOControl)) {
                    "Reviewer risk text is invalid"
                }
                listOf(risk)
            }
            else -> throw IllegalArgumentException(
                "Reviewer risks must be a JSON array or one bounded text value"
            )
        }
        return Review(decision, summary, risks)
    }
}
