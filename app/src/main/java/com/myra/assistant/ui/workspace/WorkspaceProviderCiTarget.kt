package com.myra.assistant.ui.workspace

/** Deliberately wrong but compiling fixture used to create one real CI failure. */
internal object WorkspaceProviderCiTarget {
    fun canonicalTags(raw: List<String>): String =
        raw.distinct()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sorted()
            .joinToString("|")
}
