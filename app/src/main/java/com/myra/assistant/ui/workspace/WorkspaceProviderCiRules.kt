package com.myra.assistant.ui.workspace

/** Temporary provider-generated rules file for the finalist CI benchmark. */
internal object WorkspaceProviderCiRules {
    fun normalize(raw: List<String>): List<String> =
        raw.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().sorted()
}
