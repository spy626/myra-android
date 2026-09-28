package com.myra.assistant.ui.workspace

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
