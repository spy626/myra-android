package com.myra.assistant.ui.workspace

/** Known-good baseline restored after every real provider CI benchmark. */
internal object WorkspaceProviderCiTarget {
    fun canonicalTags(raw: List<String>): String =
        raw.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString("|")
}
