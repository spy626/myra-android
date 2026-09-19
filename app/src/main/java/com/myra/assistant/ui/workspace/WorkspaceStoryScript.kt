package com.myra.assistant.ui.workspace

/** Presentation hints only. Never changes the stored transcript or grants source-file access. */
internal object WorkspaceStoryScript {
    data class Card(val title: String, val body: String, val copyText: String)

    private val creativeNoun = Regex("(?iu)(?:\\b(?:story|stories|script|screenplay|narration|kahani|kahaani|qissa|kissa)\\b|कहानी|स्क्रिप्ट|کہانی|قصہ)")
    private val requestVerb = Regex("(?iu)(?:\\b(?:write|create|make|tell|generate|draft|compose|sunao|sunana|suna|likho|likh|banao|bana|give|chahiye|chahie|sunaye)\\b|लिख|सुना|बना|سنا|لکھ)")
    private val explanation = Regex("(?iu)(?:\\b(?:explain|definition|meaning|summarize|analyse|analyze|review|critique|what is|how to|about)\\b|क्या है|समझाओ)")
    private val heading = Regex("^#{1,3}\\s+(.+?)\\s*#*\\s*$")
    private val titleLabel = Regex("(?i)^(?:\\*\\*)?(?:title|शीर्षक|عنوان)\\s*:\\s*(.+?)(?:\\*\\*)?\\s*$")

    /** Do not treat a technical question mentioning the word 'script' as a writing request. */
    fun isWritingRequest(prompt: String): Boolean {
        val text = prompt.trim().take(600)
        if (!creativeNoun.containsMatchIn(text)) return false
        if (explanation.containsMatchIn(text) && !requestVerb.containsMatchIn(text)) return false
        if (text.length <= 90 && creativeNoun.containsMatchIn(text) &&
            !explanation.containsMatchIn(text)) return true
        return requestVerb.containsMatchIn(text) && !explanation.containsMatchIn(text)
    }

    /** Only a complete titled first line is separated; never guess and drop story paragraphs. */
    fun card(prompt: String, reply: String): Card? {
        if (!isWritingRequest(prompt)) return null
        var text = reply.trim()
        if (text.isEmpty()) return null
        val fenced = Regex("(?s)^```(?:markdown|text|story|script)?\\s*\\n(.*?)\\n```$").matchEntire(text)
        if (fenced != null) text = fenced.groupValues[1].trim()
        val first = text.lineSequence().firstOrNull().orEmpty().trim()
        val labeled = heading.matchEntire(first)?.groupValues?.get(1)
            ?: titleLabel.matchEntire(first)?.groupValues?.get(1)
        val title = labeled?.trim()?.removeSurrounding("**")?.trim()?.takeIf { it.isNotBlank() && it.length <= 100 }
        val body = if (title != null) text.substringAfter('\n', "").trimStart('\n', '\r', ' ') else text
        if (body.isBlank()) return Card("Story / Script", text, cleanCopy(text))
        return Card(title ?: "Story / Script", body, cleanCopy(body))
    }

    /** Remove formatting tokens only; preserve dialogue, paragraphs and scene directions. */
    internal fun cleanCopy(text: String): String = text.lines().joinToString("\n") { line ->
        line.replace(Regex("^\\s{0,3}#{1,3}\\s+"), "")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "$1")
    }.trim()

    const val WRITING_INSTRUCTIONS: String =
        "For this explicit creative story/script request, write a usable, original piece in the " +
        "user's language and requested genre/tone. Start with one short Markdown title (# Title), " +
        "then a blank line and only the requested story or script. Use clear, naturally paced " +
        "paragraphs; for a video script, use concise scene or voice-over cues only when useful. " +
        "Honor any requested duration, length, characters and format within the output budget. " +
        "Do not add a preface, apologies, unrelated tips, follow-up offers, or a summary after the piece. " +
        "Avoid raw Markdown emphasis markers unless they improve readability. Never claim a factual " +
        "event is true when inventing fiction."
}
