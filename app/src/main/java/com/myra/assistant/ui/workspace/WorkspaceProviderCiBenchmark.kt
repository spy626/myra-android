package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Finalist multi-file CI benchmark.
 *
 * Only the two strongest current coding candidates are exercised by the runner. Providers receive
 * only these synthetic files and task contract, never LYRA project source. A failed A1 may receive
 * one same-provider A2 repair using bounded real CI evidence.
 */
internal object WorkspaceProviderCiBenchmark {
    const val RULES_PATH =
        "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceProviderCiRules.kt"
    const val TARGET_PATH =
        "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceProviderCiTarget.kt"

    val TARGET_PATHS = listOf(RULES_PATH, TARGET_PATH)

    const val BASELINE_RULES_SOURCE = """package com.myra.assistant.ui.workspace

/** Known-good rules baseline for the finalist benchmark. */
internal object WorkspaceProviderCiRules {
    fun normalize(raw: List<String>): List<String> =
        raw.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
}
"""

    const val BASELINE_TARGET_SOURCE = """package com.myra.assistant.ui.workspace

/** Known-good target baseline for the finalist benchmark. */
internal object WorkspaceProviderCiTarget {
    fun preview(raw: List<String>, limit: Int): String =
        WorkspaceProviderCiRules.normalize(raw)
            .take(limit.coerceAtLeast(0))
            .joinToString("|")

    fun count(raw: List<String>): Int =
        WorkspaceProviderCiRules.normalize(raw).size
}
"""

    const val FAILURE_RULES_SOURCE = """package com.myra.assistant.ui.workspace

/** Deliberately wrong but compiling rules fixture. */
internal object WorkspaceProviderCiRules {
    fun normalize(raw: List<String>): List<String> =
        raw.distinct()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sorted()
}
"""

    const val FAILURE_TARGET_SOURCE = """package com.myra.assistant.ui.workspace

/** Deliberately wrong but compiling target fixture. */
internal object WorkspaceProviderCiTarget {
    fun preview(raw: List<String>, limit: Int): String =
        WorkspaceProviderCiRules.normalize(raw)
            .take(limit)
            .joinToString("|")

    fun count(raw: List<String>): Int = raw.size
}
"""

    private fun taskContract(): String = buildString {
        appendLine("MULTI-FILE SYNTHETIC TASK CONTRACT:")
        appendLine("File A owns normalize(raw): trim, lowercase, remove blanks, deduplicate AFTER normalization, sort ascending.")
        appendLine("File B preview(raw, limit) MUST call WorkspaceProviderCiRules.normalize(raw), take at most limit items, join with |, and return empty for non-positive limits.")
        appendLine("File B count(raw) MUST call WorkspaceProviderCiRules.normalize(raw) and return the normalized unique count.")
        appendLine("The two files must stay coordinated through WorkspaceProviderCiRules.normalize(raw).")
        appendLine("Some edge-case acceptance tests are intentionally not enumerated; implement the contract generally.")
    }

    private fun outputContract(): String = buildString {
        appendLine("Return EXACTLY one JSON object and nothing else:")
        appendLine("{\"normalizeExpression\":\"<Kotlin expression>\",\"previewExpression\":\"<Kotlin expression>\",\"countExpression\":\"<Kotlin expression>\"}")
        appendLine("Each expression replaces only the right-hand side of its named function.")
        appendLine("Do not include return, package, imports, declarations, markdown or comments.")
    }

    fun initialPrompt(ciFailure: String): String {
        val evidence = ciFailure.trim().replace(Regex("""\s+"""), " ").take(1_200)
        require(evidence.isNotBlank()) { "CI failure evidence is missing" }
        return buildString {
            appendLine("You are implementing a SMALL TWO-FILE Kotlin change after a REAL GitHub Actions failure.")
            appendLine("Do not use tools, files, network, environment variables, reflection, processes or side effects.")
            appendLine("ACTUAL CI FAILURE CONTEXT:")
            appendLine(evidence)
            appendLine("FAILED FILE A:")
            appendLine(FAILURE_RULES_SOURCE)
            appendLine("FAILED FILE B:")
            appendLine(FAILURE_TARGET_SOURCE)
            append(taskContract())
            appendLine("Fix the task as a coordinated multi-file implementation.")
            append(outputContract())
        }
    }

    fun retryPrompt(ciFailure: String, previousSource: String): String {
        val evidence = ciFailure.trim().replace(Regex("""\s+"""), " ").take(1_200)
        val source = previousSource.trim().take(7_000)
        require(evidence.isNotBlank()) { "CI repair evidence is missing" }
        require(source.isNotBlank()) { "Previous provider source is missing" }
        return buildString {
            appendLine("Your FIRST implementation for this SAME two-file task failed REAL GitHub Actions.")
            appendLine("Repair the SAME task; do not restart or change scope.")
            appendLine("Do not use tools, files, network, environment variables, reflection, processes or side effects.")
            appendLine("ACTUAL FAILED A1 CI CONTEXT:")
            appendLine(evidence)
            appendLine("YOUR PREVIOUS TWO-FILE IMPLEMENTATION:")
            appendLine(source)
            append(taskContract())
            appendLine("Return corrected expressions for ALL THREE functions, even if only one needs changing.")
            append(outputContract())
        }
    }

    data class Prepared(
        val normalizeExpression: String,
        val previewExpression: String,
        val countExpression: String,
        val rulesSource: String,
        val targetSource: String,
    ) {
        fun combinedSource(): String =
            "FILE A:\n" + rulesSource + "\nFILE B:\n" + targetSource
    }

    private val allowedIdentifiers = setOf(
        "raw", "limit", "WorkspaceProviderCiRules", "normalize", "asSequence", "map", "it",
        "trim", "lowercase", "filter", "filterNot", "isEmpty", "isNotEmpty", "isBlank",
        "isNotBlank", "distinct", "toSet", "sorted", "toList", "take", "coerceAtLeast",
        "coerceAtMost", "coerceIn", "maxOf", "minOf", "joinToString", "size", "count",
        "if", "else", "when",
    )

    private val forbidden = Regex(
        """(?i)(?:\bSystem\b|\bRuntime\b|\bProcessBuilder\b|\bFile\b|\bFiles\b|""" +
            """\bPath\b|\bThread\b|\bClass\b|\breflect\b|\bexec\b|\bgetenv\b|""" +
            """\bexitProcess\b|\bURL\b|\bSocket\b|\bwhile\b|\bfor\b|\brepeat\b|""" +
            """java\.|javax\.|kotlin\.io|\bpackage\b|\bimport\b|\bfun\b|\bclass\b|""" +
            """\bobject\b|\bval\b|\bvar\b|//|/\*|\x60|;)"""
    )

    private fun parseSingleJsonObject(raw: String): JSONObject {
        fun parseExact(candidate: String): JSONObject? = runCatching {
            val tokener = JSONTokener(candidate.trim())
            val value = tokener.nextValue()
            require(value is JSONObject && tokener.nextClean() == '\u0000')
            value
        }.getOrNull()

        parseExact(raw)?.let { return it }

        val clean = raw.trim()
        val fenced = Regex("""(?s)^\u0060\u0060\u0060(?:json)?\s*(\{.*\})\s*\u0060\u0060\u0060$""")
            .matchEntire(clean)
            ?.groupValues
            ?.getOrNull(1)
        if (fenced != null) parseExact(fenced)?.let { return it }

        val first = clean.indexOf('{')
        val last = clean.lastIndexOf('}')
        require(first >= 0 && last > first) { "Provider CI reply contained no JSON object" }
        return parseExact(clean.substring(first, last + 1))
            ?: throw IllegalArgumentException("Provider CI reply did not contain one valid JSON object")
    }

    private fun validateExpression(expression: String, label: String): String {
        val clean = expression.trim()
        require(clean.length in 1..1_600) { "$label is missing or too large" }
        require(!clean.startsWith("return ") && !forbidden.containsMatchIn(clean)) {
            "$label contains blocked code"
        }
        val identifiers = Regex("[A-Za-z_][A-Za-z0-9_]*")
            .findAll(clean).map { it.value }.toSet()
        val unknown = identifiers - allowedIdentifiers
        require(unknown.isEmpty()) {
            "$label used non-whitelisted identifier(s): " +
                unknown.sorted().joinToString(",").take(120)
        }
        require(clean.count { it == '\n' } <= 24) { "$label is too complex" }
        return clean
    }

    fun prepare(raw: String): Prepared {
        require(raw.length in 1..9_000) { "Provider CI reply is empty or oversized" }
        val root = parseSingleJsonObject(raw)
        require(root.keys().asSequence().toSet() ==
            setOf("normalizeExpression", "previewExpression", "countExpression")) {
            "Provider CI reply used unexpected fields"
        }
        val normalize = validateExpression(
            root.optString("normalizeExpression"),
            "Provider normalize expression",
        )
        val preview = validateExpression(
            root.optString("previewExpression"),
            "Provider preview expression",
        )
        val count = validateExpression(
            root.optString("countExpression"),
            "Provider count expression",
        )
        require(preview.contains("WorkspaceProviderCiRules") &&
            preview.contains("normalize") && preview.contains("limit")) {
            "Provider preview expression did not use the cross-file normalize contract"
        }
        require(count.contains("WorkspaceProviderCiRules") &&
            count.contains("normalize") &&
            (count.contains("size") || count.contains("count"))) {
            "Provider count expression did not use the cross-file normalize contract"
        }

        val rulesSource = """package com.myra.assistant.ui.workspace

/** Temporary provider-generated rules file for the finalist CI benchmark. */
internal object WorkspaceProviderCiRules {
    fun normalize(raw: List<String>): List<String> =
        $normalize
}
"""
        val targetSource = """package com.myra.assistant.ui.workspace

/** Temporary provider-generated target file for the finalist CI benchmark. */
internal object WorkspaceProviderCiTarget {
    fun preview(raw: List<String>, limit: Int): String =
        $preview

    fun count(raw: List<String>): Int =
        $count
}
"""
        WorkspaceGitHubWritePolicy.requireContent(rulesSource)
        WorkspaceGitHubWritePolicy.requireContent(targetSource)
        return Prepared(normalize, preview, count, rulesSource, targetSource)
    }

    fun providerFiles(prepared: Prepared): List<WorkspaceGitHubWritePolicy.FileChange> = listOf(
        WorkspaceGitHubWritePolicy.FileChange(RULES_PATH, prepared.rulesSource),
        WorkspaceGitHubWritePolicy.FileChange(TARGET_PATH, prepared.targetSource),
    )

    fun failureFiles(): List<WorkspaceGitHubWritePolicy.FileChange> = listOf(
        WorkspaceGitHubWritePolicy.FileChange(RULES_PATH, FAILURE_RULES_SOURCE),
        WorkspaceGitHubWritePolicy.FileChange(TARGET_PATH, FAILURE_TARGET_SOURCE),
    )

    fun baselineFiles(): List<WorkspaceGitHubWritePolicy.FileChange> = listOf(
        WorkspaceGitHubWritePolicy.FileChange(RULES_PATH, BASELINE_RULES_SOURCE),
        WorkspaceGitHubWritePolicy.FileChange(TARGET_PATH, BASELINE_TARGET_SOURCE),
    )
}
