package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener

/** Small synthetic coding task used only to compare provider behavior without project source. */
internal object WorkspaceProviderBakeOff {
    const val PROMPT =
        "Solve three tiny Kotlin bug fixes. Return EXACTLY one JSON object, no markdown or prose, " +
            "with only keys t1, t2, t3. Values must be corrected single Kotlin return lines.\n" +
            "T1: fun isEven(value: Int): Boolean { return value % 2 == 1 }\n" +
            "T2: fun clamp(value: Int, min: Int, max: Int): Int { return value.coerceIn(min + 1, max - 1) }\n" +
            "T3: fun firstTag(tags: List<String>): String { return tags.first() } // must return empty string when list is empty\n" +
            "Expected JSON shape only: {\"t1\":\"...\",\"t2\":\"...\",\"t3\":\"...\"}"

    data class Evaluation(
        val strictJson: Boolean,
        val t1: Boolean,
        val t2: Boolean,
        val t3: Boolean,
    ) {
        val correct: Int get() = listOf(t1, t2, t3).count { it }
        val total: Int get() = 3
        val passed: Boolean get() = strictJson && correct == total
    }

    private data class Parsed(val root: JSONObject?, val strictJson: Boolean)

    private fun parse(raw: String): Parsed {
        val clean = raw.trim()
        val strict = runCatching {
            val tokener = JSONTokener(clean)
            val value = tokener.nextValue()
            require(value is JSONObject && tokener.nextClean() == '\u0000')
            value
        }.getOrNull()
        if (strict != null) {
            return Parsed(
                root = strict,
                strictJson = strict.keys().asSequence().toSet() == setOf("t1", "t2", "t3"),
            )
        }

        // Keep format compliance separate from semantic correctness. This lets the bake-off show
        // that a provider solved the code but violated the machine-readable response contract.
        val first = clean.indexOf('{')
        val last = clean.lastIndexOf('}')
        if (first < 0 || last <= first) return Parsed(null, strictJson = false)
        val embedded = runCatching {
            JSONObject(clean.substring(first, last + 1))
        }.getOrNull()
        return Parsed(embedded, strictJson = false)
    }

    private fun expression(root: JSONObject, key: String): String? {
        val raw = root.opt(key) as? String ?: return null
        var value = raw.trim()
        if (value.startsWith("return ")) value = value.removePrefix("return ").trim()
        value = value.removeSuffix(";").trim()
        return value.replace(Regex("\\s+"), "")
    }

    private fun t1Correct(value: String?): Boolean = value in setOf(
        "value%2==0",
        "value.rem(2)==0",
        "value.mod(2)==0",
    )

    private fun t2Correct(value: String?): Boolean = value in setOf(
        "value.coerceIn(min,max)",
        "maxOf(min,minOf(max,value))",
        "minOf(max,maxOf(min,value))",
    )

    private fun t3Correct(value: String?): Boolean = value in setOf(
        "tags.firstOrNull().orEmpty()",
        "tags.firstOrNull()?:\"\"",
        "tags.getOrNull(0).orEmpty()",
        "if(tags.isEmpty())\"\"elsetags.first()",
        "if(tags.isNotEmpty())tags.first()else\"\"",
    )

    fun evaluate(raw: String): Evaluation {
        val parsed = parse(raw)
        val root = parsed.root
            ?: return Evaluation(strictJson = false, t1 = false, t2 = false, t3 = false)
        return Evaluation(
            strictJson = parsed.strictJson,
            t1 = t1Correct(expression(root, "t1")),
            t2 = t2Correct(expression(root, "t2")),
            t3 = t3Correct(expression(root, "t3")),
        )
    }
}
