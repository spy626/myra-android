package com.myra.assistant.ui.workspace

/** Source-only coherence guard after LYRA's native Explore anchor repair.
 * Only recognized redundant scroll/visual-feedback listeners are removed on a NEW site.
 * Unrecognized JS is never silently rewritten; this is not a full JS parser or phone test.
 */
internal object WorkspaceWebsiteScriptQuality {
    private const val IDENT = """[A-Za-z_\$][A-Za-z0-9_\$]*"""
    private val directListener = Regex(
        """(?s)\bdocument\s*\.\s*getElementById\s*\(\s*(['"])([a-zA-Z][\w:.-]{0,63})\1\s*\)\s*\.\s*addEventListener\s*\(""")
    private val boundId = Regex(
        """(?s)\b(?:const|let|var)\s+($IDENT)\s*=\s*document\s*\.\s*getElementById\s*\(\s*(['"])([a-zA-Z][\w:.-]{0,63})\2\s*\)""")
    private val elementId = Regex("""(?is)\bid\s*=\s*(['"])([a-zA-Z][\w:.-]{0,63})\1""")
    private val nativeExplore = Regex(
        """(?is)<a\b[^>]*\bhref\s*=\s*(['"])#([a-zA-Z][\w:.-]{0,63})\1[^>]*>\s*Explore\s+Minicoy\s*</a\s*>""")
    private val heading = Regex(
        """(?is)<h[1-6]\b[^>]*\bid\s*=\s*(['"])([a-zA-Z][\w:.-]{0,63})\1[^>]*>\s*Things\s+to\s+Explore\s*</h[1-6]\s*>""")

    private fun fresh(snapshot: WorkspaceWebsiteGeneration.Snapshot): Boolean =
        snapshot.original.values.all { it.isNullOrBlank() } ||
            (snapshot.original["index.html"]?.contains("<h1>Hello, Workspace!</h1>") == true &&
                snapshot.original["style.css"]?.contains("body { margin: 0; padding: 2rem;") == true &&
                snapshot.original["script.js"]?.contains("// Your JavaScript starts here.") == true)

    // Whitespace/comment normalization is used ONLY to compare an entire short script
    // against narrowly enumerated harmless patterns. Never execute or edit a substring.
    private fun compact(script: String): String {
        val noComments = script.replace(Regex("""(?m)^\s*//[^\r\n]*(?:\r?\n|$)"""), "")
            .replace(Regex("""(?m)\s+//[^\r\n]*"""), "")
        return noComments.replace(Regex("""\s+"""), "")
    }

    private val smoothBody = """function\((?<event>$IDENT)\)\{\k<event>\.preventDefault\(\);?""" +
        """(?:const|let|var)(?<target>$IDENT)=document\.getElementById\((?:'things-to-explore'|"things-to-explore"|'lyra-explore-section'|"lyra-explore-section")\);?""" +
        """if\(\k<target>\)\{\k<target>\.scrollIntoView\(\{behavior:(?:'smooth'|"smooth")\}\);?""" +
        """(?:\k<target>\.setAttribute\((?:'tabindex'|"tabindex"),(?:'-1'|"-1")\);?\k<target>\.focus\(\);?)?\}\}"""
    private val basicGuard = """(?:const|let|var)(?<button>$IDENT)=document\.getElementById\((?:'exploreBtn'|"exploreBtn")\);?""" +
        """if\(\k<button>\)\{\k<button>\.addEventListener\((?:'click'|"click"),$smoothBody\);?\}"""
    private val directScrollOnly = Regex(
        """^document\.getElementById\((?:'exploreBtn'|"exploreBtn")\)\.addEventListener\((?:'click'|"click"),$smoothBody\);?$""")
    private val guardedScrollOnly = Regex("^$basicGuard;?$")
    private val domReadyScrollOnly = Regex(
        """^document\.addEventListener\((?:'DOMContentLoaded'|"DOMContentLoaded"),function\(\)\{$basicGuard\}\);?$""")

    /** Exact single-purpose variant visible in the September 21 phone recording.
     * Its old timer/highlight is redundant with the native anchor and CSS :target feedback.
     * The full-script match forbids extra effects, network calls or unrelated listeners.
     */
    private val recordedDomVisualOnly = Regex(
        """^document\.addEventListener\((?:'DOMContentLoaded'|"DOMContentLoaded"),function\(\)\{""" +
        """(?:const|let|var)(?<button>$IDENT)=document\.getElementById\((?:'exploreBtn'|"exploreBtn")\);?""" +
        """if\(\k<button>\)\{\k<button>\.addEventListener\((?:'click'|"click"),""" +
        """function\((?<event>$IDENT)\)\{\k<event>\.preventDefault\(\);?""" +
        """(?:const|let|var)(?<target>$IDENT)=document\.querySelector\(this\.getAttribute\((?:'href'|"href")\)\);?""" +
        """if\(\k<target>\)\{\k<target>\.scrollIntoView\(\{behavior:(?:'smooth'|"smooth")\}\);?""" +
        """\k<target>\.style\.transition=(?:'background0\.5s'|"background0\.5s");?""" +
        """(?:const|let|var)(?<original>$IDENT)=\k<target>\.style\.backgroundColor;?""" +
        """\k<target>\.style\.backgroundColor=(?:'#fff3cd'|"#fff3cd");?""" +
        """setTimeout\(\(\)=>\{\k<target>\.style\.backgroundColor=\k<original>;?\},800\);?""" +
        """\}\}\);?\}\}\);?$""")

    private fun isolatedNativeEquivalent(script: String): Boolean {
        if (script.length > 2_500) return false
        val normalized = compact(script)
        return normalized.length <= 1_500 && listOf(
            directScrollOnly, guardedScrollOnly, domReadyScrollOnly, recordedDomVisualOnly
        ).any { it.matches(normalized) }
    }

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               files: Map<String, String>): Map<String, String> {
        val html = files["index.html"] ?: return files
        val script = files["script.js"] ?: return files
        if (script.isBlank()) return files
        val ids = elementId.findAll(html).map { it.groupValues[2] }.toSet()
        val directIds = directListener.findAll(script).map { it.groupValues[2] }.toList()
        val boundIds = boundId.findAll(script).mapNotNull { binding ->
            val variable = binding.groupValues[1]
            val usesClickListener = Regex("""\b${Regex.escape(variable)}\s*\.\s*addEventListener\s*\(""")
                .containsMatchIn(script)
            binding.groupValues[3].takeIf { usesClickListener }
        }.toList()
        val missing = (directIds + boundIds).filterNot { it in ids }.distinct()
        val nativeLink = nativeExplore.findAll(html).toList().singleOrNull()
        val target = nativeLink?.groupValues?.get(2)
        val eligible = fresh(snapshot) &&
            snapshot.goal.contains("Explore Minicoy", true) &&
            snapshot.goal.contains("Things to Explore", true) &&
            nativeLink != null && heading.findAll(html).any { it.groupValues[2] == target }
        val knownRedundant = eligible && isolatedNativeEquivalent(script)

        // A matching ID can still suppress navigation when its listener calls
        // preventDefault. Reconcile that exact known duplicate too, not arbitrary JS.
        if (knownRedundant) {
            return files + ("script.js" to
                "// Explore Minicoy uses the native in-page link and visible :target feedback.\n")
        }
        if (missing.isNotEmpty()) throw IllegalArgumentException(
            "Website JavaScript references a missing HTML click target; no files changed")
        return files
    }
}
