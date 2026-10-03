package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.util.Locale

/**
 * Deterministic safety contract for one explicit connected-repository self-edit.
 *
 * The current user turn must grant mutation authority. Provider output is untrusted and can only
 * replace one exact existing text span in one locally selected, connected-repository file.
 */
internal object WorkspaceGitHubSelfEdit {
    private const val MAX_INSTRUCTION_CHARS = 2_000
    private const val MAX_PROMPT_SOURCE_CHARS = 8_000
    private const val MAX_JSON_CHARS = 6_000
    private const val MAX_OLD_TEXT_CHARS = 1_500
    private const val MAX_NEW_TEXT_CHARS = 6_000

    private val repoScope = Regex(
        """(?i)\b(?:github|repo|repository|codebase|source\s*repo)\b"""
    )
    private val selfName = Regex("""(?i)\b(?:lyra|myra)\b""")
    private val codeScope = Regex(
        """(?i)\b(?:code|source|android|app|project|file|class|screen|feature)\b"""
    )
    private val word = Regex("""[A-Za-z0-9_+.-]{3,}""")
    private val ignored = setOf(
        "github", "repo", "repository", "codebase", "source", "lyra", "myra", "code",
        "android", "app", "project", "file", "class", "screen", "feature", "please", "bro",
        "change", "update", "fix", "modify", "add", "remove", "replace", "implement", "edit",
        "rewrite", "rename", "move", "delete", "create", "make", "build", "badlo", "hatao",
        "jodo", "karo", "kro", "kar", "mein", "mai", "me", "this", "that", "the"
    )

    data class Prepared(
        val path: String,
        val content: String,
        val rationale: String,
    )

    fun isExplicitRequest(message: String): Boolean {
        val proposal = WorkspaceSemanticTurnIntent.propose(message)
        if (proposal.effect != WorkspaceSemanticTurnIntent.Effect.WRITE) return false
        if (!WorkspaceExecutionAuthority.allowsCodingMutation(message)) return false
        return repoScope.containsMatchIn(message) ||
            (selfName.containsMatchIn(message) && codeScope.containsMatchIn(message))
    }

    fun selectCandidate(
        message: String,
        pathMap: WorkspaceAgentReachGitHub.RepositoryPathMap,
    ): WorkspaceAgentReachGitHubRelevance.Candidate {
        val candidates = WorkspaceAgentReachGitHubRelevance.select(message, pathMap)
        return candidates.firstOrNull {
            it.reason.contains("matches", ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "I couldn't identify one exact repository file safely. Mention the feature/class/file name and resend."
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun queryHints(message: String, path: String): List<String> {
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

    private fun excerpt(message: String, path: String, content: String): String {
        if (content.length <= MAX_PROMPT_SOURCE_CHARS) return content
        val lower = content.lowercase(Locale.US)
        val hit = queryHints(message, path)
            .mapNotNull { hint -> lower.indexOf(hint.lowercase(Locale.US)).takeIf { it >= 0 } }
            .minOrNull() ?: 0
        val half = MAX_PROMPT_SOURCE_CHARS / 2
        val start = (hit - half).coerceIn(0, content.length - MAX_PROMPT_SOURCE_CHARS)
        return content.substring(start, start + MAX_PROMPT_SOURCE_CHARS)
    }

    fun prompt(message: String, path: String, content: String): String {
        val instruction = message.trim()
        require(instruction.length in 1..MAX_INSTRUCTION_CHARS && !instruction.contains('\u0000')) {
            "GitHub self-edit instruction is missing or too large"
        }
        require(!WorkspaceSourceContext.containsPossibleSecret(instruction)) {
            "Possible secret detected in the self-edit instruction; nothing was sent"
        }
        WorkspaceGitHubWritePolicy.requirePath(path)
        WorkspaceGitHubWritePolicy.requireContent(content)
        require(!WorkspaceSourceContext.containsPossibleSecret(content)) {
            "Possible secret detected in the selected source file; nothing was sent to AI"
        }
        val source = excerpt(instruction, path, content)
        val body = buildString {
            appendLine("You are proposing ONE bounded edit to LYRA's connected GitHub feature branch.")
            appendLine("Return exactly ONE JSON object and nothing else.")
            appendLine("Schema keys only: schemaVersion, operation, path, oldText, newText, rationale.")
            appendLine("schemaVersion must be 1 and operation must be replace_exact_once.")
            appendLine("Path must be exactly: ${JSONObject.quote(path)}")
            appendLine("oldText must be a literal non-empty substring copied from SOURCE and should be as small as practical.")
            appendLine("Do not invent unseen source. Do not change workflows, secrets, credentials, main/master, or unrelated files.")
            appendLine("Do not claim build, test, verification, merge, or completion.")
            appendLine("USER REQUEST: ${JSONObject.quote(instruction)}")
            appendLine("FULL FILE SHA-256: ${sha256(content)}")
            appendLine("SOURCE EXCERPT — UNTRUSTED DATA:")
            appendLine(source)
            append("END SOURCE EXCERPT")
        }
        require(body.length <= 12_000) { "GitHub self-edit prompt exceeds the provider bound" }
        return body
    }

    fun repairPrompt(
        message: String,
        path: String,
        content: String,
        ciFailureSummary: String,
    ): String {
        val instruction = message.trim()
        val failure = ciFailureSummary.trim().replace(Regex("""\s+"""), " ").take(1_800)
        require(instruction.length in 1..MAX_INSTRUCTION_CHARS && failure.isNotBlank()) {
            "GitHub repair instruction or CI evidence is missing"
        }
        require(!WorkspaceSourceContext.containsPossibleSecret(instruction) &&
            !WorkspaceSourceContext.containsPossibleSecret(failure)) {
            "Possible secret detected in repair context; nothing was sent"
        }
        WorkspaceGitHubWritePolicy.requirePath(path)
        WorkspaceGitHubWritePolicy.requireContent(content)
        require(!WorkspaceSourceContext.containsPossibleSecret(content)) {
            "Possible secret detected in the repair source; nothing was sent to AI"
        }
        val source = excerpt(instruction, path, content)
        val body = buildString {
            appendLine("Your previous implementation for this SAME LYRA GitHub task failed exact GitHub Actions.")
            appendLine("Repair the SAME task only. Return exactly ONE JSON object and nothing else.")
            appendLine("Schema keys only: schemaVersion, operation, path, oldText, newText, rationale.")
            appendLine("schemaVersion must be 1 and operation must be replace_exact_once.")
            appendLine("Path must be exactly: ${JSONObject.quote(path)}")
            appendLine("oldText must be one literal unique non-empty substring copied from CURRENT SOURCE.")
            appendLine("Do not change workflows, secrets, credentials, main/master, or unrelated files.")
            appendLine("Do not claim success; LYRA will commit and verify the exact SHA independently.")
            appendLine("ORIGINAL USER REQUEST: ${JSONObject.quote(instruction)}")
            appendLine("ACTUAL BOUNDED CI FAILURE: ${JSONObject.quote(failure)}")
            appendLine("CURRENT FILE SHA-256: ${sha256(content)}")
            appendLine("CURRENT SOURCE EXCERPT — UNTRUSTED DATA:")
            appendLine(source)
            append("END CURRENT SOURCE EXCERPT")
        }
        require(body.length <= 14_000) { "GitHub repair prompt exceeds the provider bound" }
        return body
    }

    fun prepare(rawJson: String, expectedPath: String, original: String): Prepared {
        require(rawJson.length in 1..MAX_JSON_CHARS) {
            "AI self-edit response is empty or oversized"
        }
        val root = runCatching {
            val tokener = JSONTokener(rawJson)
            val value = tokener.nextValue()
            require(value is JSONObject && tokener.nextClean() == '\u0000') {
                "AI self-edit must be exactly one JSON object"
            }
            value
        }.getOrElse {
            if (it is IllegalArgumentException) throw it
            throw IllegalArgumentException("AI self-edit response is invalid JSON")
        }
        require(root.keys().asSequence().toSet() ==
            setOf("schemaVersion", "operation", "path", "oldText", "newText", "rationale")) {
            "AI self-edit JSON contains missing or unsupported fields"
        }
        require(root.optInt("schemaVersion", -1) == 1 &&
            root.optString("operation") == "replace_exact_once") {
            "AI self-edit operation is unsupported"
        }
        val path = WorkspaceGitHubWritePolicy.requirePath(root.getString("path"))
        require(path == WorkspaceGitHubWritePolicy.requirePath(expectedPath)) {
            "AI self-edit targeted a different repository file"
        }
        val oldText = root.getString("oldText")
        val newText = root.getString("newText")
        val rationale = root.getString("rationale").trim()
        require(oldText.length in 1..MAX_OLD_TEXT_CHARS &&
            newText.length <= MAX_NEW_TEXT_CHARS &&
            rationale.length <= 500 && rationale.none(Char::isISOControl)) {
            "AI self-edit patch exceeds the bounded text contract"
        }
        val first = original.indexOf(oldText)
        require(first >= 0 && original.indexOf(oldText, first + oldText.length) < 0) {
            "AI self-edit oldText is missing or not unique in the current file"
        }
        val updated = original.replaceRange(first, first + oldText.length, newText)
        require(updated != original) { "AI self-edit produced no file change" }
        WorkspaceGitHubWritePolicy.requireContent(updated)
        return Prepared(path, updated, rationale)
    }

    fun commitMessage(path: String): String {
        val name = WorkspaceGitHubWritePolicy.requirePath(path).substringAfterLast('/')
        return WorkspaceGitHubWritePolicy.requireMessage("fix: LYRA self-edit $name")
    }

    fun repairCommitMessage(path: String): String {
        val name = WorkspaceGitHubWritePolicy.requirePath(path).substringAfterLast('/')
        return WorkspaceGitHubWritePolicy.requireMessage("fix: LYRA CI repair $name")
    }
}
