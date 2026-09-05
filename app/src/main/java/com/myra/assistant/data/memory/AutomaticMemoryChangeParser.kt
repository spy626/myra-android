package com.myra.assistant.data.memory

sealed class AutomaticMemoryChange {
    data class Save(val candidate: MemoryCandidate) : AutomaticMemoryChange()
    data class Forget(val stableKey: String) : AutomaticMemoryChange()
}

object AutomaticMemoryChangeParser {
    fun parse(raw: String): AutomaticMemoryChange? {
        val text = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        negativeEnglishSubject(text)?.let { subject ->
            return forgetPreference(subject)
        }
        negativeHinglishSubject(text)?.let { subject ->
            return forgetPreference(subject)
        }

        val corrected = text.replace(
            Regex(
                """^(?:actually|correction|now|ab)\s*[,;:\-]?\s*""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        return AutomaticMemoryExtractor.extract(corrected)
            ?.let(AutomaticMemoryChange::Save)
    }

    private fun negativeEnglishSubject(text: String): String? =
        Regex(
            """^i\s+(?:(?:do\s+not|don't|dont)\s+(?:like|love|enjoy)|no\s+longer\s+(?:like|love|enjoy))\s+(.+?)(?:\s+anymore)?$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.groupValues?.get(1)

    private fun negativeHinglishSubject(text: String): String? =
        Regex(
            """^mujhe\s+(.+?)\s+(?:ab\s+)?pasand[ae]?\s+nahi\s+(?:hai|hain|he)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.groupValues?.get(1)

    private fun forgetPreference(rawSubject: String): AutomaticMemoryChange? {
        val positive = AutomaticMemoryExtractor.extract("I like ${rawSubject.trim()}")
            ?: return null
        return AutomaticMemoryChange.Forget(positive.stableKey)
    }
}
