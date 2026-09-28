package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Real multi-step compile/test benchmark contract.
 *
 * Providers receive only this synthetic fixture, never repository/project source. Two bounded
 * expressions are wrapped into one fixed Kotlin file and committed only to the protected feature
 * branch. A failed first provider attempt may receive one bounded exact-CI repair pass.
 */
internal object WorkspaceProviderCiBenchmark {
    const val TARGET_PATH =
        "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceProviderCiTarget.kt"

    const val BASELINE_SOURCE = """package com.myra.assistant.ui.workspace

/** Known-good baseline restored after every real provider CI benchmark. */
internal object WorkspaceProviderCiTarget {
    fun normalizedTags(raw: List<String>): List<String> =
        raw.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()

    fun previewTags(raw: List<String>, limit: Int): String =
        normalizedTags(raw)
            .take(limit.coerceAtLeast(0))
            .joinToString("|")
}
"""

    const val FAILURE_SOURCE = """package com.myra.assistant.ui.workspace

/** Deliberately wrong but compiling two-step fixture used to create one real CI failure. */
internal object WorkspaceProviderCiTarget {
    fun normalizedTags(raw: List<String>): List<String> =
        raw.distinct()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sorted()

    fun previewTags(raw: List<String>, limit: Int): String =
        normalizedTags(raw)
            .take(limit)
            .joinToString("|")
}
"""

    private fun acceptanceContract(): String = buildString {
        appendLine("SYNTHETIC ACCEPTANCE TEST CONTRACT:")
        appendLine("- normalizedTags(emptyList()) must be empty")
        appendLine("- normalizedTags([\" Kotlin \",\"LYRA\",\"\",\" android \",\"kotlin\",\"  \"]) must be [android,kotlin,lyra]")
        appendLine("- normalizedTags([\" B \",\"a\",\"A\",\"b\",\" a \"]) must be [a,b]")
        appendLine("- previewTags(the first list, 2) must be android|kotlin")
        appendLine("- previewTags(the first list, 99) must be android|kotlin|lyra")
        appendLine("- previewTags(the first list, 0) must be empty")
        appendLine("- previewTags(the first list, -3) must be empty and must not throw")
        appendLine("The preview must use normalizedTags(raw), so both functions stay coordinated.")
    }

    private fun outputContract(): String = buildString {
        appendLine("Return EXACTLY one JSON object and nothing else:")
        appendLine("{\"normalizeExpression\":\"<Kotlin expression>\",\"previewExpression\":\"<Kotlin expression>\"}")
        appendLine("normalizeExpression replaces only the RHS of normalizedTags.")
        appendLine("previewExpression replaces only the RHS of previewTags.")
        appendLine("Do not include return, package, imports, declarations, markdown or comments.")
    }

    fun initialPrompt(ciFailure: String): String {
        val evidence = ciFailure.trim().replace(Regex("""\s+"""), " ").take(1_200)
        require(evidence.isNotBlank()) { "CI failure evidence is missing" }
        return buildString {
            appendLine("You are completing TWO coordinated synthetic Kotlin functions after a REAL GitHub Actions failure.")
            appendLine("Do not use tools, files, network, environment variables, reflection, processes or side effects.")
            appendLine("The execution evidence below is authoritative; do not claim success without fixing both contracts.")
            appendLine("ACTUAL CI FAILURE CONTEXT:")
            appendLine(evidence)
            appendLine("FAILED SYNTHETIC SOURCE:")
            appendLine(FAILURE_SOURCE)
            append(acceptanceContract())
            appendLine("Infer both bugs from the failed source and acceptance tests.")
            append(outputContract())
        }
    }

    fun retryPrompt(ciFailure: String, previousSource: String): String {
        val evidence = ciFailure.trim().replace(Regex("""\s+"""), " ").take(1_200)
        val source = previousSource.trim().take(4_500)
        require(evidence.isNotBlank()) { "CI repair evidence is missing" }
        require(source.isNotBlank()) { "Previous provider source is missing" }
        return buildString {
            appendLine("Your FIRST implementation for this same synthetic task failed REAL GitHub Actions.")
            appendLine("Repair the SAME task. Do not restart, change scope, or claim success without satisfying all tests.")
            appendLine("Do not use tools, files, network, environment variables, reflection, processes or side effects.")
            appendLine("ACTUAL FAILED ATTEMPT CI CONTEXT:")
            appendLine(evidence)
            appendLine("YOUR PREVIOUS SYNTHETIC SOURCE:")
            appendLine(source)
            append(acceptanceContract())
            appendLine("Return corrected expressions for BOTH functions, even if only one needs changing.")
            append(outputContract())
        }
    }

    data class Prepared(
        val normalizeExpression: String,
        val previewExpression: String,
        val source: String,
    )

    private val allowedIdentifiers = setOf(
        "raw", "limit", "normalizedTags", "asSequence", "map", "it", "trim", "lowercase",
        "filter", "filterNot", "isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "distinct",
        "toSet", "sorted", "toList", "take", "coerceAtLeast", "joinToString",
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
            ?: throw IllegalArgumentException(
                "Provider CI reply did not contain one valid JSON object")
    }

    private fun validateExpression(expression: String, label: String): String {
        val clean = expression.trim()
        require(clean.length in 1..1_500) { "$label is missing or too large" }
        require(!clean.startsWith("return ") && !forbidden.containsMatchIn(clean)) {
            "$label contains blocked code"
        }
        val identifiers = Regex("[A-Za-z_][A-Za-z0-9_]*")
            .findAll(clean).map { it.value }.toSet()
        require(identifiers.all { it in allowedIdentifiers }) {
            "$label used a non-whitelisted identifier"
        }
        require(clean.count { it == '\n' } <= 24) { "$label is too complex" }
        return clean
    }

    fun prepare(raw: String): Prepared {
        require(raw.length in 1..8_000) { "Provider CI reply is empty or oversized" }
        val root = parseSingleJsonObject(raw)
        require(root.keys().asSequence().toSet() ==
            setOf("normalizeExpression", "previewExpression")) {
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
        require(preview.contains("normalizedTags") && preview.contains("limit")) {
            "Provider preview expression did not coordinate with normalizedTags and limit"
        }

        val source = """package com.myra.assistant.ui.workspace

/** Temporary provider-generated implementation for the real multi-step CI benchmark. */
internal object WorkspaceProviderCiTarget {
    fun normalizedTags(raw: List<String>): List<String> =
        $normalize

    fun previewTags(raw: List<String>, limit: Int): String =
        $preview
}
"""
        WorkspaceGitHubWritePolicy.requireContent(source)
        return Prepared(normalize, preview, source)
    }
}
