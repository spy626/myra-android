package com.myra.assistant.ui.workspace

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
