package com.myra.assistant.ui.workspace

/** Narrow source-only JS/HTML coherence check after the native Explore anchor repair.
 * A generated click listener cannot safely refer to a button ID removed by the anchor
 * replacement. Only the exact, isolated redundant smooth-scroll listener seen on phone
 * may be removed on a NEW site. Other missing bindings fail closed without file writes.
 * This is not a JavaScript parser or proof that arbitrary scripts run correctly.
 */
internal object WorkspaceWebsiteScriptQuality {
    private val directListener = Regex(
        """(?s)\bdocument\s*\.\s*getElementById\s*\(\s*(['"])([a-zA-Z][\w:.-]{0,63})\1\s*\)\s*\.\s*addEventListener\s*\(""")
    private val elementId = Regex("""(?is)\bid\s*=\s*(['"])([a-zA-Z][\w:.-]{0,63})\1""")
    private val nativeExplore = Regex(
        """(?is)<a\b[^>]*\bhref\s*=\s*(['"])#([a-zA-Z][\w:.-]{0,63})\1[^>]*>\s*Explore\s+Minicoy\s*</a\s*>""")
    private val heading = Regex("""(?is)<h[1-6]\b[^>]*\bid\s*=\s*(['"])([a-zA-Z][\w:.-]{0,63})\1[^>]*>\s*Things\s+to\s+Explore\s*</h[1-6]\s*>""")

    private fun fresh(snapshot: WorkspaceWebsiteGeneration.Snapshot): Boolean =
        snapshot.original.values.all { it.isNullOrBlank() } ||
            (snapshot.original["index.html"]?.contains("<h1>Hello, Workspace!</h1>") == true &&
                snapshot.original["style.css"]?.contains("body { margin: 0; padding: 2rem;") == true &&
                snapshot.original["script.js"]?.contains("// Your JavaScript starts here.") == true)

    /** Exactly the recorded, single-purpose listener. No arbitrary scripts are deleted.
     * Removing it restores native fragment navigation AND visible :target feedback.
     */
    private val recordedScrollOnly = Regex(
        """^document\.getElementById\(['"]exploreBtn['"]\)\.addEventListener\(['"]click['"],function\(e\)\{e\.preventDefault\(\);consttarget=document\.getElementById\(['"]things-to-explore['"]\);if\(target\)\{target\.scrollIntoView\(\{behavior:['"]smooth['"]\}\);target\.setAttribute\(['"]tabindex['"],['"]-1['"]\);target\.focus\(\);\}\}\);?$""")

    private fun isolatedRecordedScroll(script: String): Boolean {
        val noLineComments = script.replace(Regex("""(?m)^\s*//[^\r\n]*(?:\r?\n|$)"""), "")
        val compact = noLineComments.replace(Regex("""\s+"""), "")
        return compact.length <= 900 && recordedScrollOnly.matches(compact)
    }

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               files: Map<String, String>): Map<String, String> {
        val html = files["index.html"] ?: return files
        val script = files["script.js"] ?: return files
        if (script.isBlank()) return files
        val ids = elementId.findAll(html).map { it.groupValues[2] }.toSet()
        val missing = directListener.findAll(script).map { it.groupValues[2] }
            .filterNot { it in ids }.distinct().toList()
        if (missing.isEmpty()) return files
        val link = nativeExplore.findAll(html).toList().singleOrNull()
        val target = link?.groupValues?.get(2)
        val safeRecordedCase = fresh(snapshot) &&
            snapshot.goal.contains("Explore Minicoy", true) &&
            snapshot.goal.contains("Things to Explore", true) &&
            missing == listOf("exploreBtn") && link != null &&
            heading.findAll(html).any { it.groupValues[2] == target } &&
            isolatedRecordedScroll(script)
        if (!safeRecordedCase) throw IllegalArgumentException(
            "Website JavaScript references a missing HTML click target; no files changed")
        return files + ("script.js" to
            "// Explore Minicoy uses the native in-page link and visible :target feedback.\n")
    }
}
