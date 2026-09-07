package com.myra.assistant.data.memory

sealed class AutomaticMemoryChange {
    data class Save(val candidate: MemoryCandidate) : AutomaticMemoryChange()
    data class Forget(val stableKey: String) : AutomaticMemoryChange()
}

object AutomaticMemoryChangeParser {
    fun parse(raw: String): AutomaticMemoryChange? {
        val text = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        AutomaticMemoryExtractor.extract(raw)?.let { return AutomaticMemoryChange.Save(it) }

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
}
