package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.json.JSONTokener

/** One tiny synthetic coding task used only to compare provider behavior without project source. */
internal object WorkspaceProviderBakeOff {
    const val PROMPT =
        "Solve three tiny Kotlin bug fixes. Return EXACTLY one JSON object, no markdown or prose, " +
            "with only keys t1, t2, t3. Values must be the corrected single Kotlin return lines.\n" +
            "T1: fun isEven(value: Int): Boolean { return value % 2 == 1 }\n" +
            "T2: fun clamp(value: Int, min: Int, max: Int): Int { return value.coerceIn(min + 1, max - 1) }\n" +
            "T3: fun firstTag(tags: List<String>): String { return tags.first() } // must return empty string when list is empty\n" +
            "Expected JSON shape only: {\"t1\":\"...\",\"t2\":\"...\",\"t3\":\"...\"}"

    private val expected = mapOf(
        "t1" to "return value % 2 == 0",
        "t2" to "return value.coerceIn(min, max)",
        "t3" to "return tags.firstOrNull().orEmpty()",
    )

    data class Evaluation(
        val strictJson: Boolean,
        val correct: Int,
        val total: Int = expected.size,
    ) {
        val passed: Boolean get() = strictJson && correct == total
    }

    fun evaluate(raw: String): Evaluation {
        val clean = raw.trim()
        val root = runCatching {
            val tokener = JSONTokener(clean)
            val value = tokener.nextValue()
            require(value is JSONObject && tokener.nextClean() == '\u0000')
            value
        }.getOrNull() ?: return Evaluation(strictJson = false, correct = 0)

        if (root.keys().asSequence().toSet() != expected.keys) {
            return Evaluation(strictJson = false, correct = 0)
        }
        val correct = expected.count { entry ->
            root.optString(entry.key).trim() == entry.value
        }
        return Evaluation(strictJson = true, correct = correct)
    }
}
