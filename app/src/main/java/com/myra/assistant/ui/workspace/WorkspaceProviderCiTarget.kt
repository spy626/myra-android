package com.myra.assistant.ui.workspace

/** Temporary provider-generated target file for the finalist CI benchmark. */
internal object WorkspaceProviderCiTarget {
    fun preview(raw: List<String>, limit: Int): String =
        if (limit <= 0) "" else WorkspaceProviderCiRules.normalize(raw).take(limit).joinToString("|")

    fun count(raw: List<String>): Int =
        WorkspaceProviderCiRules.normalize(raw).size
}
