package com.myra.assistant.ui.workspace

/** Temporary provider-generated implementation for the real multi-step CI benchmark. */
internal object WorkspaceProviderCiTarget {
    fun normalizedTags(raw: List<String>): List<String> =
        raw.distinct().map { it.trim().lowercase() }.filter { it.isNotBlank() }.sorted()

    fun previewTags(raw: List<String>, limit: Int): String =
        normalizedTags(raw).take(limit.coerceAtLeast(0)).joinToString("|")
}
