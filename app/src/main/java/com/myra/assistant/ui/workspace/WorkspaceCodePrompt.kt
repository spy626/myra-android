package com.myra.assistant.ui.workspace

/** Presentation instructions only; does not modify user text or introduce project authority. */
internal object WorkspaceCodePrompt {
    private val codeTerms = Regex(
        """(?i)\b(?:code|coding|html|css|javascript|typescript|python|kotlin|java|react|sql|swift|php|program|script|function|snippet)\b|कोड|کوڈ"""
    )

    fun instructions(latest: String): String = if (codeTerms.containsMatchIn(latest)) """
Code-answer formatting when supplying code: put a short explanation outside the code fence; use one complete Markdown fenced code block with the correct language tag per file. Put programming identifiers, filenames and comments in English unless the user explicitly asks for a different code language; keep user-requested UI strings exactly as requested. For a tiny standalone HTML demonstration, prefer one complete index.html with inline CSS and JavaScript, valid /* CSS comments */, working event handlers and closing tags. Do not write Markdown backticks inside the code itself. Do not claim that code was executed or tested unless it was actually run.
""".trim() else ""
}
