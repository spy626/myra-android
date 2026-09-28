package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Real compile/test benchmark contract.
 *
 * Providers receive only this synthetic fixture, never repository/project source. Their expression
 * is wrapped into one fixed Kotlin file and committed only to the protected feature branch.
 */
internal object WorkspaceProviderCiBenchmark {
    const val TARGET_PATH =
        "app/src/main/java/com/myra/assistant/ui/workspace/WorkspaceProviderCiTarget.kt"

    const val BASELINE_SOURCE = """package com.myra.assistant.ui.workspace

/** Known-good baseline restored after every real provider CI benchmark. */
internal object WorkspaceProviderCiTarget {
    fun canonicalTags(raw: List<String>): String =
        raw.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString("|")
}
"""

    const val FAILURE_SOURCE = """package com.myra.assistant.ui.workspace

/** Deliberately wrong but compiling fixture used to create one real CI failure. */
internal object WorkspaceProviderCiTarget {
    fun canonicalTags(raw: List<String>): String =
        raw.distinct()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sorted()
            .joinToString("|")
}
"""

    fun repairPrompt(ciFailure: String): String {
        val evidence = ciFailure.trim().replace(Regex("""\s+"""), " ").take(1_200)
        require(evidence.isNotBlank()) { "CI failure evidence is missing" }
        return buildString {
            appendLine("You are repairing ONE synthetic Kotlin function after a REAL GitHub Actions failure.")
            appendLine("Do not use tools, files, network, environment variables, reflection, processes or side effects.")
            appendLine("The execution evidence below is authoritative; do not claim success without fixing the source.")
            appendLine("ACTUAL CI FAILURE CONTEXT:")
            appendLine(evidence)
            appendLine("FAILED SYNTHETIC SOURCE:")
            appendLine(FAILURE_SOURCE)
            appendLine("SYNTHETIC ACCEPTANCE TEST CONTRACT:")
            appendLine("- emptyList() must become an empty string")
            appendLine("- [\" Kotlin \",\"LYRA\",\"\",\" android \",\"kotlin\",\"  \"] must become android|kotlin|lyra")
            appendLine("- [\" B \",\"a\",\"A\",\"b\",\" a \"] must become a|b")
            appendLine("Infer the bug from the failed source and tests.")
            appendLine("Return EXACTLY one JSON object and nothing else:")
            appendLine("{\"expression\":\"<one Kotlin expression>\"}")
            appendLine("The expression replaces only the right-hand side after '='.")
            appendLine("Do not include return, package, imports, declarations, markdown or comments.")
        }
    }

    data class Prepared(val expression: String, val source: String)

    private val allowedIdentifiers = setOf(
        "raw", "asSequence", "map", "it", "trim", "lowercase", "filter", "filterNot",
        "isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "distinct", "toSet", "sorted",
        "joinToString",
    )

    private val forbidden = Regex(
        """(?i)(?:\bSystem\b|\bRuntime\b|\bProcessBuilder\b|\bFile\b|\bFiles\b|""" +
            """\bPath\b|\bThread\b|\bClass\b|\breflect\b|\bexec\b|\bgetenv\b|""" +
            """\bexitProcess\b|\bURL\b|\bSocket\b|\bwhile\b|\bfor\b|\brepeat\b|""" +
            """java\.|javax\.|kotlin\.io|\bpackage\b|\bimport\b|\bfun\b|\bclass\b|""" +
            """\bobject\b|//|/\*|\x60|;)"""
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

        // Some models add prose despite the JSON-only instruction. Ignore it entirely; only the
        // bounded JSON expression below can reach compilation and it still passes the allow-list.
        val first = clean.indexOf('{')
        val last = clean.lastIndexOf('}')
        require(first >= 0 && last > first) { "Provider CI reply contained no JSON object" }
        return parseExact(clean.substring(first, last + 1))
            ?: throw IllegalArgumentException(
                "Provider CI reply did not contain one valid JSON object")
    }

    fun prepare(raw: String): Prepared {
        require(raw.length in 1..6_000) { "Provider CI reply is empty or oversized" }
        val root = parseSingleJsonObject(raw)
        require(root.keys().asSequence().toSet() == setOf("expression")) {
            "Provider CI reply used unexpected fields"
        }
        val expression = root.optString("expression").trim()
        require(expression.length in 1..1_200) { "Provider CI expression is missing or too large" }
        require(!expression.startsWith("return ") && !forbidden.containsMatchIn(expression)) {
            "Provider CI expression contains blocked code"
        }
        val identifiers = Regex("[A-Za-z_][A-Za-z0-9_]*")
            .findAll(expression).map { it.value }.toSet()
        require(identifiers.all { it in allowedIdentifiers }) {
            "Provider CI expression used a non-whitelisted identifier"
        }
        require(expression.count { it == '\n' } <= 20) {
            "Provider CI expression is too complex"
        }

        val source = """package com.myra.assistant.ui.workspace

/** Temporary provider-generated implementation for the real CI benchmark. */
internal object WorkspaceProviderCiTarget {
    fun canonicalTags(raw: List<String>): String =
        $expression
}
"""
        WorkspaceGitHubWritePolicy.requireContent(source)
        return Prepared(expression, source)
    }
}
