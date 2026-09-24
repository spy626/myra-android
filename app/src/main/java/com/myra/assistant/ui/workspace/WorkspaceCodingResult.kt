package com.myra.assistant.ui.workspace

/** Honest, source-derived summaries: no invented claims of visual or phone testing. */
internal object WorkspaceCodingResult {
    fun websiteSuccess(previous: Map<String, String?>, generated: Map<String, String>): String {
        val changed = WorkspaceWebsiteGeneration.PATHS.filter { previous[it] != generated[it] }
        val files = if (changed.isEmpty())
            "Saved website already matched the generated result."
        else "Saved ${changed.joinToString(", ")}."
        val nativeNote = if (generated["script.js"] == WorkspaceWebsiteNativeActionOwner.REPLACEMENT)
            " Explore uses LYRA's local safe action owner; test the interaction in Preview."
        else ""
        return "Done — $files Local saved-file verification passed. Preview is ready." +
            nativeNote + " Review website has Undo / Keep; visual phone testing remains separate."
    }

    fun failure(reason: String): String = "LYRA coding request complete nahi kar paayi: $reason " +
        "Work → Files mein current project check karo. Agar Review edit/website dikhe " +
        "toh Undo ya Keep ke baad same instruction dobara bhej sakte ho." +
        if (reason.contains("No paid fallback", ignoreCase = true)) "" else " No paid fallback."
}
