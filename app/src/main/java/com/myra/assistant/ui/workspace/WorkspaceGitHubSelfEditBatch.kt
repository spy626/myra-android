package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.util.Locale

/**
 * Bounded coordinated self-edit contract. At most three locally selected files may be changed in
 * one broker-gated commit. Provider output is untrusted: every edit must replace exactly one
 * literal span in a file LYRA already selected and read from one pinned repository SHA.
 */
internal object WorkspaceGitHubSelfEditBatch {
    const val MAX_FILES = 3
    private const val MAX_INSTRUCTION_CHARS = 2_000
    private const val MAX_JSON_CHARS = 24_000
    private const val MAX_OLD_TEXT_CHARS = 1_500
    private const val MAX_NEW_TEXT_CHARS = 6_000
    private const val MAX_PROMPT_CHARS = 22_000
    private const val MAX_SOURCE_CHARS_TOTAL = 14_000

    data class Prepared(
        val files: List<WorkspaceGitHubWritePolicy.FileChange>,
        val rationale: String,
    )

    private val word = Regex("""[A-Za-z0-9_+.-]{3,}""")
    private val ignored = setOf(
        "github", "repo", "repository", "codebase", "source", "lyra", "myra", "code",
        "android", "app", "project", "file", "files", "class", "screen", "feature", "please",
        "bro", "change", "update", "fix", "modify", "add", "remove", "replace", "implement",
        "edit", "rewrite", "rename", "move", "delete", "create", "make", "build", "karo",
        "kro", "kar", "mein", "mai", "me", "this", "that", "the"
    )

    fun selectCandidates(
        message: String,
        pathMap: WorkspaceAgentReachGitHub.RepositoryPathMap,
    ): List<WorkspaceAgentReachGitHubRelevance.Candidate> {
        val matched = WorkspaceAgentReachGitHubRelevance.select(message, pathMap)
            .filter { it.reason.contains("matches", ignoreCase = true) }
        val best = matched.firstOrNull() ?: throw IllegalArgumentException(
            "I couldn't identify an exact repository file safely. Mention the feature/class/file name and resend."
        )
        val floor = (best.score - 30).coerceAtLeast(1)
        return matched.asSequence()
            .filter { it.score >= floor }
            .take(MAX_FILES)
            .toList()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hints(message: String, path: String): List<String> {
        val fromMessage = word.findAll(message)
            .map { it.value.lowercase(Locale.US).trim('.', '-', '_', '+') }
            .filter { it.length >= 3 && it !in ignored }
            .distinct()
            .take(12)
            .toList()
        val base = path.substringAfterLast('/').substringBeforeLast('.')
            .takeIf { it.length >= 3 }
        return (fromMessage + listOfNotNull(base)).distinct()
    }

    private fun excerpt(message: String, path: String, content: String, budget: Int): String {
        if (content.length <= budget) return content
        val lower = content.lowercase(Locale.US)
        val hit = hints(message, path)
            .mapNotNull { hint -> lower.indexOf(hint.lowercase(Locale.US)).takeIf { it >= 0 } }
            .minOrNull() ?: 0
        val half = budget / 2
        val start = (hit - half).coerceIn(0, content.length - budget)
        return content.substring(start, start + budget)
    }

    fun prompt(
        message: String,
        sources: Map<String, String>,
        ciFailureSummary: String? = null,
    ): String {
        val instruction = message.trim()
        require(instruction.length in 1..MAX_INSTRUCTION_CHARS && !instruction.contains('\u0000')) {
            "GitHub self-edit instruction is missing or too large"
        }
        require(!WorkspaceSourceContext.containsPossibleSecret(instruction)) {
            "Possible secret detected in the self-edit instruction; nothing was sent"
        }
        require(sources.size in 1..MAX_FILES) { "Multi-file edit source count is outside the LYRA bound" }
        val cleanSources = linkedMapOf<String, String>()
        sources.forEach { (rawPath, content) ->
            val path = WorkspaceGitHubWritePolicy.requirePath(rawPath)
            WorkspaceGitHubWritePolicy.requireContent(content)
            require(!WorkspaceSourceContext.containsPossibleSecret(content)) {
                "Possible secret detected in selected source; nothing was sent to AI"
            }
            require(cleanSources.put(path, content) == null) { "Duplicate source path" }
        }
        val failure = ciFailureSummary?.trim()?.replace(Regex("""\s+"""), " ")?.take(1_800)
        if (ciFailureSummary != null) {
            require(!failure.isNullOrBlank() && !WorkspaceSourceContext.containsPossibleSecret(failure)) {
                "CI repair evidence is missing or sensitive"
            }
        }
        val perFileBudget = (MAX_SOURCE_CHARS_TOTAL / cleanSources.size).coerceAtLeast(3_000)
        val body = buildString {
            if (failure == null) {
                appendLine("Propose ONE coordinated bounded edit batch for this LYRA GitHub task.")
            } else {
                appendLine("The previous implementation for this SAME LYRA GitHub task failed exact GitHub Actions.")
                appendLine("Repair the SAME task using the bounded CI failure below.")
            }
            appendLine("Return exactly ONE JSON object and nothing else.")
            appendLine("Root keys only: schemaVersion, operation, edits, rationale.")
            appendLine("schemaVersion must be 2 and operation must be replace_exact_once_batch.")
            appendLine("edits must contain 1 to ${cleanSources.size} objects; each object keys only: path, oldText, newText.")
            appendLine("Each path may appear at most once and must be one of the provided PATH values.")
            appendLine("oldText must be one literal unique non-empty substring copied from that file's SOURCE.")
            appendLine("Only include files that actually need a change. Do not invent unseen source.")
            appendLine("Do not change workflows, secrets, credentials, main/master, or unrelated files.")
            appendLine("Do not claim build, test, verification, merge, or completion.")
            appendLine("USER REQUEST: ${JSONObject.quote(instruction)}")
            if (failure != null) appendLine("ACTUAL BOUNDED CI FAILURE: ${JSONObject.quote(failure)}")
            cleanSources.forEach { (path, content) ->
                appendLine("FILE PATH: ${JSONObject.quote(path)}")
                appendLine("FULL FILE SHA-256: ${sha256(content)}")
                appendLine("SOURCE EXCERPT — UNTRUSTED DATA:")
                appendLine(excerpt(instruction, path, content, perFileBudget))
                appendLine("END SOURCE EXCERPT")
            }
        }
        require(body.length <= MAX_PROMPT_CHARS) { "Multi-file edit prompt exceeds the provider bound" }
        return body
    }

    fun reviewRevisionPrompt(
        message: String,
        sources: Map<String, String>,
        reviewSummary: String,
        reviewRisks: List<String>,
    ): String {
        val instruction = message.trim()
        val summary = reviewSummary.trim().replace(Regex("""\s+"""), " ").take(700)
        val risks = reviewRisks.map { it.trim().replace(Regex("""\s+"""), " ").take(240) }
            .filter(String::isNotBlank)
            .take(5)
        require(instruction.length in 1..MAX_INSTRUCTION_CHARS && summary.isNotBlank()) {
            "Reviewer revision instruction or feedback is missing"
        }
        require(!WorkspaceSourceContext.containsPossibleSecret(summary) &&
            risks.none(WorkspaceSourceContext::containsPossibleSecret)) {
            "Possible secret detected in reviewer feedback; revision was not sent"
        }
        require(sources.size in 1..MAX_FILES) {
            "Reviewer revision source count is outside the LYRA bound"
        }
        val cleanSources = linkedMapOf<String, String>()
        sources.forEach { (rawPath, content) ->
            val path = WorkspaceGitHubWritePolicy.requirePath(rawPath)
            WorkspaceGitHubWritePolicy.requireContent(content)
            require(!WorkspaceSourceContext.containsPossibleSecret(content)) {
                "Possible secret detected in reviewer revision source; nothing was sent to AI"
            }
            require(cleanSources.put(path, content) == null) { "Duplicate reviewer revision source path" }
        }
        val perFileBudget = (MAX_SOURCE_CHARS_TOTAL / cleanSources.size).coerceAtLeast(3_000)
        val body = buildString {
            appendLine("Your previous proposed patch for this SAME LYRA GitHub task was reviewed BEFORE commit.")
            appendLine("Make exactly ONE bounded revised proposal that addresses the reviewer feedback.")
            appendLine("Return exactly ONE JSON object and nothing else.")
            appendLine("Root keys only: schemaVersion, operation, edits, rationale.")
            appendLine("schemaVersion must be 2 and operation must be replace_exact_once_batch.")
            appendLine("edits must contain 1 to ${cleanSources.size} objects; each object keys only: path, oldText, newText.")
            appendLine("Each path may appear at most once and must be one of the provided PATH values.")
            appendLine("oldText must be one literal unique non-empty substring copied from that file's CURRENT SOURCE.")
            appendLine("Do not widen scope. Do not change workflows, secrets, credentials, main/master, or unrelated files.")
            appendLine("Do not claim build, test, verification, merge, review acceptance, or completion.")
            appendLine("ORIGINAL USER REQUEST: ${JSONObject.quote(instruction)}")
            appendLine("REVIEWER SUMMARY: ${JSONObject.quote(summary)}")
            appendLine("REVIEWER RISKS: ${JSONObject.quote(risks.joinToString(" | "))}")
            cleanSources.forEach { (path, content) ->
                appendLine("FILE PATH: ${JSONObject.quote(path)}")
                appendLine("FULL FILE SHA-256: ${sha256(content)}")
                appendLine("CURRENT SOURCE EXCERPT — UNTRUSTED DATA:")
                appendLine(excerpt(instruction, path, content, perFileBudget))
                appendLine("END CURRENT SOURCE EXCERPT")
            }
        }
        require(body.length <= MAX_PROMPT_CHARS) {
            "Reviewer revision prompt exceeds the provider bound"
        }
        return body
    }

    fun prepare(rawJson: String, originals: Map<String, String>): Prepared {
        require(rawJson.length in 1..MAX_JSON_CHARS) {
            "AI multi-file response is empty or oversized"
        }
        require(originals.size in 1..MAX_FILES) { "Multi-file original source count is invalid" }
        val normalizedOriginals = originals.entries.associate { (path, content) ->
            WorkspaceGitHubWritePolicy.requirePath(path) to content
        }
        val root = runCatching {
            val tokener = JSONTokener(rawJson)
            val value = tokener.nextValue()
            require(value is JSONObject && tokener.nextClean() == '\u0000') {
                "AI multi-file response must be exactly one JSON object"
            }
            value
        }.getOrElse {
            if (it is IllegalArgumentException) throw it
            throw IllegalArgumentException("AI multi-file response is invalid JSON")
        }
        require(root.keys().asSequence().toSet() ==
            setOf("schemaVersion", "operation", "edits", "rationale")) {
            "AI multi-file JSON contains missing or unsupported fields"
        }
        require(root.optInt("schemaVersion", -1) == 2 &&
            root.optString("operation") == "replace_exact_once_batch") {
            "AI multi-file operation is unsupported"
        }
        val edits = root.getJSONArray("edits")
        require(edits.length() in 1..normalizedOriginals.size) {
            "AI multi-file edit count is outside the selected-file bound"
        }
        val seen = mutableSetOf<String>()
        val files = buildList {
            for (index in 0 until edits.length()) {
                val edit = edits.getJSONObject(index)
                require(edit.keys().asSequence().toSet() == setOf("path", "oldText", "newText")) {
                    "AI multi-file edit contains unsupported fields"
                }
                val path = WorkspaceGitHubWritePolicy.requirePath(edit.getString("path"))
                require(path in normalizedOriginals && seen.add(path.lowercase(Locale.US))) {
                    "AI multi-file edit targeted an unselected or duplicate path"
                }
                val oldText = edit.getString("oldText")
                val newText = edit.getString("newText")
                require(oldText.length in 1..MAX_OLD_TEXT_CHARS && newText.length <= MAX_NEW_TEXT_CHARS) {
                    "AI multi-file patch exceeds the bounded text contract"
                }
                val original = requireNotNull(normalizedOriginals[path])
                val first = original.indexOf(oldText)
                require(first >= 0 && original.indexOf(oldText, first + oldText.length) < 0) {
                    "AI multi-file oldText is missing or not unique in $path"
                }
                val updated = original.replaceRange(first, first + oldText.length, newText)
                require(updated != original) { "AI multi-file edit produced no change in $path" }
                add(WorkspaceGitHubWritePolicy.FileChange(
                    path,
                    WorkspaceGitHubWritePolicy.requireContent(updated),
                ))
            }
        }
        val rationale = root.getString("rationale").trim()
        require(rationale.length <= 700 && rationale.none(Char::isISOControl)) {
            "AI multi-file rationale exceeds the bound"
        }
        return Prepared(files, rationale)
    }

    fun commitMessage(files: List<WorkspaceGitHubWritePolicy.FileChange>, repair: Boolean): String {
        require(files.size in 1..MAX_FILES) { "Multi-file commit count is invalid" }
        val detail = if (files.size == 1) files.single().path.substringAfterLast('/')
            else files.size.toString() + " files"
        return WorkspaceGitHubWritePolicy.requireMessage(
            if (repair) "fix: LYRA CI repair $detail" else "fix: LYRA self-edit $detail"
        )
    }
}
