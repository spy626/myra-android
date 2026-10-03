package com.myra.assistant.ui.workspace

/** Source-only check for the exact all-anchor smooth-scroll script observed on phone.
 * The script prevents the URL fragment from changing, suppressing CSS :target feedback.
 * Only a fresh site with the explicitly requested, verified native Explore destination
 * may discard this ENTIRE single-purpose script. Unknown scripts fail closed.
 */
internal object WorkspaceWebsiteAnchorScriptQuality {
    private val nativeExplore = Regex(
        """(?is)<a\b[^>]*\bhref\s*=\s*(['"])#([a-zA-Z][\w:.-]{0,63})\1[^>]*>\s*Explore\s+Minicoy\s*</a\s*>""")
    private val heading = Regex(
        """(?is)<h[1-6]\b[^>]*\bid\s*=\s*(['"])([a-zA-Z][\w:.-]{0,63})\1[^>]*>\s*Things\s+to\s+Explore\s*</h[1-6]\s*>""")
    private val interceptsFragments = Regex(
        """(?s)querySelectorAll\s*\(\s*(['"])a\[href\^=['"]#['"]\]\1\s*\)""")

    // Backreferences require one consistent anchor, event and target variable, and
    // exclude any extra code, network call, timer, unrelated button or side effect.
    private val recordedScrollOnly = Regex(
        """^document\.querySelectorAll\((['"])a\[href\^=(['"])#\2\]\1\)\.forEach\(([A-Za-z_]\w*)=>\{\3\.addEventListener\((['"])click\4,function\(([A-Za-z_]\w*)\)\{\5\.preventDefault\(\);(?:const|let|var)([A-Za-z_]\w*)=document\.querySelector\(this\.getAttribute\((['"])href\7\)\);if\(\6\)\{\6\.scrollIntoView\(\{behavior:(['"])smooth\8\}\);\6\.setAttribute\((['"])tabindex\9,(['"])-1\10\);\6\.focus\(\);\}\}\);\}\);?$""")

    private fun fresh(snapshot: WorkspaceWebsiteGeneration.Snapshot): Boolean =
        snapshot.original.values.all { it.isNullOrBlank() } ||
            (snapshot.original["index.html"]?.contains("<h1>Hello, Workspace!</h1>") == true &&
                snapshot.original["style.css"]?.contains("body { margin: 0; padding: 2rem;") == true &&
                snapshot.original["script.js"]?.contains("// Your JavaScript starts here.") == true)

    private fun compact(script: String): String = script
        .replace(Regex("""(?m)^[ \t]*//[^\r\n]*"""), "")
        .replace(Regex("""(?m)[ \t]+//[^\r\n]*"""), "")
        .replace(Regex("""\s+"""), "")

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               files: Map<String, String>): Map<String, String> {
        val script = files["script.js"] ?: return files
        if (!interceptsFragments.containsMatchIn(script) || !script.contains("preventDefault")) return files
        if (!fresh(snapshot) || !snapshot.goal.contains("Explore Minicoy", true) ||
            !snapshot.goal.contains("Things to Explore", true)) return files
        val html = files["index.html"].orEmpty()
        val link = nativeExplore.findAll(html).toList().singleOrNull()
        val target = link?.groupValues?.get(2)
        val destinationVerified = target != null &&
            heading.findAll(html).any { it.groupValues[2] == target } &&
            html.contains("class=\"lyra-explore-feedback\"") &&
            files["style.css"].orEmpty().contains(".lyra-explore-target:target + .lyra-explore-feedback")
        require(destinationVerified && script.length <= 2_500 && recordedScrollOnly.matches(compact(script))) {
            "Website JavaScript intercepts in-page links without verified feedback; no files changed"
        }
        return files + ("script.js" to
            "// Explore Minicoy uses the native in-page link and visible :target feedback.\n")
    }
}
