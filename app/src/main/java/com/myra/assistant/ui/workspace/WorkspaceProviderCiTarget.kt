package com.myra.assistant.ui.workspace

/** Deliberately wrong but compiling target fixture. */
internal object WorkspaceProviderCiTarget {
    fun preview(raw: List<String>, limit: Int): String =
        WorkspaceProviderCiRules.normalize(raw)
            .take(limit)
            .joinToString("|")

    fun count(raw: List<String>): Int = raw.size
}
