package com.myra.assistant.ui.workspace

/** Honest, source-derived summaries: no invented claims of visual or phone testing. */
internal object WorkspaceCodingResult {
    fun websiteSuccess(previous: Map<String, String?>, generated: Map<String, String>): String {
        val changed = WorkspaceWebsiteGeneration.PATHS.any { previous[it] != generated[it] }
        val status = if (changed) "Website is ready." else "Website is already up to date."
        val actionNote = if (generated["script.js"] == WorkspaceWebsiteNativeActionOwner.REPLACEMENT)
            " Test the Explore button in Preview."
        else ""
        return "$status Preview is ready.$actionNote"
    }

    fun failure(reason: String): String = "LYRA coding request complete nahi kar paayi: $reason " +
        "Work → Files mein current project check karo. Agar Review edit/website dikhe " +
        "toh Undo ya Keep ke baad same instruction dobara bhej sakte ho." +
        if (reason.contains("No paid fallback", ignoreCase = true)) "" else " No paid fallback."
}
