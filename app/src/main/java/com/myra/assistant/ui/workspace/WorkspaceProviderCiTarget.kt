package com.myra.assistant.ui.workspace

/** Known-good target baseline for the finalist benchmark. */
internal object WorkspaceProviderCiTarget {
    fun preview(raw: List<String>, limit: Int): String =
        WorkspaceProviderCiRules.normalize(raw)
            .take(limit.coerceAtLeast(0))
            .joinToString("|")

    fun count(raw: List<String>): Int =
        WorkspaceProviderCiRules.normalize(raw).size
}
