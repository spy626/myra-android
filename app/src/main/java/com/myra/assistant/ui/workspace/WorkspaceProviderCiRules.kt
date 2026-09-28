package com.myra.assistant.ui.workspace

/** Known-good rules baseline for the finalist benchmark. */
internal object WorkspaceProviderCiRules {
    fun normalize(raw: List<String>): List<String> =
        raw.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
}
