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

    private const val BUGGY_SOURCE = """package com.myra.assistant.ui.workspace

internal object WorkspaceProviderCiTarget {
    fun canonicalTags(raw: List<String>): String =
        raw.joinToString("|")
}
"""

    val PROMPT: String = buildString {
        appendLine("You are solving ONE synthetic Kotlin coding benchmark.")
        appendLine("Do not use tools, files, network, environment variables, reflection, processes or side effects.")
        appendLine("Goal for canonicalTags(raw):")
        appendLine("- trim every item")
        appendLine("- lowercase it")
        appendLine("- remove blank values")
        appendLine("- deduplicate AFTER normalization")
        appendLine("- sort ascending")
        appendLine("- join with the literal separator |")
        appendLine("Return EXACTLY one JSON object and nothing else:")
        appendLine("{\"expression\":\"<one Kotlin expression>\"}")
        appendLine("The expression replaces only the right-hand side after '='.")
        appendLine("Do not include return, package, imports, declarations, markdown or comments.")
        appendLine("SYNTHETIC SOURCE:")
        append(BUGGY_SOURCE)
    }

    data class Prepared(val expression: String, val source: String)

    private val forbidden = Regex(
        """(?i)(?:\bSystem\b|\bRuntime\b|\bProcessBuilder\b|\bFile\b|\bFiles\b|""" +
            """\bPath\b|\bThread\b|\bClass\b|\breflect\b|\bexec\b|\bgetenv\b|""" +
            """\bexitProcess\b|\bURL\b|\bSocket\b|\bwhile\b|\bfor\b|\brepeat\b|""" +
            """java\.|javax\.|kotlin\.io|\bpackage\b|\bimport\b|\bfun\b|\bclass\b|""" +
            """\bobject\b|//|/\*|\x60|;)"""
    )

    fun prepare(raw: String): Prepared {
        require(raw.length in 1..6_000) { "Provider CI reply is empty or oversized" }
        val clean = raw.trim()
        val root = runCatching {
            val tokener = JSONTokener(clean)
            val value = tokener.nextValue()
            require(value is JSONObject && tokener.nextClean() == '\u0000')
            value
        }.getOrElse {
            throw IllegalArgumentException("Provider CI reply was not strict JSON")
        }
        require(root.keys().asSequence().toSet() == setOf("expression")) {
            "Provider CI reply used unexpected fields"
        }
        val expression = root.optString("expression").trim()
        require(expression.length in 1..1_200) { "Provider CI expression is missing or too large" }
        require(!expression.startsWith("return ") && !forbidden.containsMatchIn(expression)) {
            "Provider CI expression contains blocked code"
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
