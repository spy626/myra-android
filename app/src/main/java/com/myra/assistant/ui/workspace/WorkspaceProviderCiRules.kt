package com.myra.assistant.ui.workspace

/** Deliberately wrong but compiling rules fixture. */
internal object WorkspaceProviderCiRules {
    fun normalize(raw: List<String>): List<String> =
        raw.distinct()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .sorted()
}
