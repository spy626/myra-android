package com.myra.assistant.ui.workspace

/** Temporary provider-generated implementation for the real CI benchmark. */
internal object WorkspaceProviderCiTarget {
    fun canonicalTags(raw: List<String>): String =
        raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("|")
}
