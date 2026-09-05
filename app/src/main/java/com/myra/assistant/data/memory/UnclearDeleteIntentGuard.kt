package com.myra.assistant.data.memory

object UnclearDeleteIntentGuard {
    private val deleteWord = Regex("\\b(?:delete|remove|uninstall|hata|hatao|hata\\s+do)\\b", RegexOption.IGNORE_CASE)

    fun needsClarification(raw: String): Boolean =
        deleteWord.containsMatchIn(raw) && MemoryCommandParser.parse(raw) == null
}
