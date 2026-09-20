package com.myra.assistant.ui.workspace

/** A narrow fresh-project action-ownership contract, not a JavaScript parser.
 * For the explicitly requested Minicoy one-action page, native href + CSS :target
 * are the complete interaction. Newly generated, competing Explore-only JS is
 * discarded with a visible explanation. Existing code, unrelated behaviors,
 * inline scripts and unapproved/unsafe effects are never silently rewritten.
 */
internal object WorkspaceWebsiteNativeActionOwner {
    const val REPLACEMENT =
        "// LYRA omitted unverified generated Explore JavaScript; native in-page link and CSS :target own this action.\n"

    private val otherRequestedAction = Regex(
        """(?i)\b(?:form|login|sign.?up|register|search|filter|menu|dropdown|carousel|slider|tabs|modal|popup|alert|api|fetch|timer|game|checkout|payment|toggle|submit|calculator|chat|another\s+button|second\s+button|dusra\s+button)\b""")
    private val link = Regex(
        """(?is)<a\b[^>]*\bhref\s*=\s*(['"])#([a-zA-Z][\w:.-]{0,63})\1[^>]*>\s*Explore\s+Minicoy\s*</a\s*>""")
    private val scriptTag = Regex("""(?is)<script\b([^>]*)>""")
    private val localScript = Regex("""(?is)\bsrc\s*=\s*(['"])script\.js\1""")
    private val extraControl = Regex("""(?is)<(?:button|form|input|select|textarea)\b""")
    private val inlineHandler = Regex("""(?is)<[^>]*\bon[a-z]+\s*=""")
    private val dangerousEffect = Regex(
        """(?i)\b(?:fetch|XMLHttpRequest|WebSocket|eval|import|postMessage|sendBeacon|localStorage|sessionStorage|alert)\b|\.\s*(?:click|submit|removeChild|appendChild)\s*\(""")

    fun review(snapshot: WorkspaceWebsiteGeneration.Snapshot,
               files: Map<String, String>): Map<String, String> {
        // A manually created or previously generated project owns its own JavaScript.
        if (snapshot.original.values.any { it != null }) return files
        val goal = snapshot.goal
        if (listOf("Minicoy", "Explore Minicoy", "Things to Explore", "Exploring Minicoy!",
                "Beaches", "Lighthouse", "Local Food").any { !goal.contains(it, true) } ||
            otherRequestedAction.containsMatchIn(goal)) return files
        val html = files["index.html"] ?: return files
        val css = files["style.css"] ?: return files
        val script = files["script.js"] ?: return files
        if (script.isBlank() || script == REPLACEMENT) return files
        // Do not mistake harmless unrelated JS for an Explore click interceptor.
        val competing = script.contains("exploreBtn", true) ||
            (script.contains("preventDefault", true) && script.contains("scrollIntoView", true))
        if (!competing) return files

        val anchor = link.findAll(html).toList().singleOrNull()
        val target = anchor?.groupValues?.get(2)
        val heading = if (target == null) null else Regex(
            """(?is)<h[1-6]\b[^>]*\bid\s*=\s*(['"])${Regex.escape(target)}\1[^>]*>\s*Things\s+to\s+Explore\s*</h[1-6]\s*>""")
            .find(html)
        require(heading != null &&
            html.contains("class=\"lyra-explore-feedback\"") &&
            css.contains(".lyra-explore-target:target + .lyra-explore-feedback")) {
            "Native Explore feedback cannot be verified; no files changed"
        }
        // Do not discard code that could serve an additional control or execute an
        // unrelated side effect. A new, one-action page has exactly one local script.
        val scripts = scriptTag.findAll(html).toList()
        require(scripts.size == 1 && localScript.containsMatchIn(scripts.single().groupValues[1]) &&
            !extraControl.containsMatchIn(html) && !inlineHandler.containsMatchIn(html) &&
            !html.contains("javascript:", true) && !dangerousEffect.containsMatchIn(script)) {
            "Unverified generated JavaScript has other effects; no files changed"
        }
        return files + ("script.js" to REPLACEMENT)
    }
}
