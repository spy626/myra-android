package com.myra.assistant.ui.workspace

/** Honest, source-derived summaries: no invented claims of visual or phone testing. */
internal object WorkspaceCodingResult {
    fun websiteSuccess(previous: Map<String, String?>, generated: Map<String, String>): String {
        val changed = WorkspaceWebsiteGeneration.PATHS.filter { previous[it] != generated[it] }
        val files = if (changed.isEmpty())
            "Generated website matches the previous saved files; no content changes."
        else "Website files saved: ${changed.joinToString(", ")}."
        val titles = Regex("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]\\s*>")
            .findAll(generated["index.html"].orEmpty()).map { match ->
                match.groupValues[1].replace(Regex("<[^>]*>"), " ")
                    .replace(Regex("\\s+"), " ").trim().take(65)
            }.filter { it.isNotEmpty() && it.none(Char::isISOControl) }
            .distinct().take(5).toList()
        val headings = if (titles.isEmpty()) "" else "\nSaved page headings: ${titles.joinToString(", ")}."
        return "$files$headings\nWork → Preview mein result check karo. " +
            "Review website · Undo / Keep sirf pending change ke liye hai. " +
            "Visual aur button testing aapko phone par confirm karni hai."
    }

    fun failure(reason: String): String = "LYRA coding request complete nahi kar paayi: $reason " +
        "Work → Files mein current project check karo. Agar Review edit/website dikhe " +
        "toh Undo ya Keep ke baad same instruction dobara bhej sakte ho. " +
        "No paid fallback."
}
