package com.myra.assistant.ui.workspace

/** Chat-only writing presentation. The stored transcript remains the provider's original reply. */
internal object WorkspaceStoryScript {
    data class Card(
        val title: String,
        val body: String,
        val copyText: String,
        val intro: String = "",
        val tip: String? = null,
    )

    private val creativeNoun = Regex("(?iu)(?:\\b(?:story|stories|script|screenplay|narration|kahani|kahaani|qissa|kissa)\\b|कहानी|स्क्रिप्ट|کہانی|قصہ)")
    private val narrativeNoun = Regex("(?iu)(?:\\b(?:story|stories|screenplay|narration|kahani|kahaani|qissa|kissa)\\b|कहानी|کہانی|قصہ)")
    private val requestVerb = Regex("(?iu)(?:\\b(?:write|create|make|tell|generate|draft|compose|sunao|sunana|suna|likho|likh|banao|bana|give|chahiye|chahie|sunaye)\\b|लिख|सुना|बना|سنا|لکھ)")
    private val explanation = Regex("(?iu)^(?:please\\s+)?(?:explain|define|summarize|analyse|analyze|review|critique|what is|how to|meaning of|definition of|क्या है|समझाओ)\\b")
    private val technicalScript = Regex("(?iu)\\b(?:python|javascript|bash|shell|automation|automate|terminal|function|script\\.js|code|coding|programming)\\b")
    private val videoNoun = Regex("(?iu)(?:\\b(?:video|reel|shorts|youtube|voice[ -]?over|film|filming|shoot|recording)\\b|वीडियो|ویڈیو)")
    private val heading = Regex("^#{1,3}\\s+(.+?)\\s*#*\\s*$")
    private val titleLabel = Regex("(?i)^(?:\\*\\*)?(?:title|शीर्षक|عنوان)\\s*:\\s*(.+?)(?:\\*\\*)?\\s*$")
    // Whole-line markers are deliberately simple for small free models. Never inspect or mutate files.
    private val section = Regex("(?im)^[ \\t]*(?:#{1,3}[ \\t]*)?(?:\\*\\*)?(INTRO|TITLE|SCRIPT|VIDEO[ \\t]+TIP)(?:\\*\\*)?[ \\t]*:[ \\t]*([^\\n]*)$")

    fun isWritingRequest(prompt: String): Boolean {
        val text = prompt.trim().take(600)
        if (!creativeNoun.containsMatchIn(text) || explanation.containsMatchIn(text)) return false
        if (!narrativeNoun.containsMatchIn(text) && technicalScript.containsMatchIn(text)) return false
        return requestVerb.containsMatchIn(text) || text.length <= 90
    }

    fun isVideoRequest(prompt: String): Boolean = videoNoun.containsMatchIn(prompt.take(600))

    /** Fall back to the original full story if a free model omits a marker; never discard prose. */
    fun card(prompt: String, reply: String): Card? {
        if (!isWritingRequest(prompt)) return null
        var text = reply.trim().replace("\r\n", "\n")
        if (text.isEmpty()) return null
        val fenced = Regex("(?s)^```(?:markdown|text|story|script)?\\s*\\n(.*?)\\n```$").matchEntire(text)
        if (fenced != null) text = fenced.groupValues[1].trim()
        val video = isVideoRequest(prompt)
        val sections = section.findAll(text).toList()
        val scriptAt = sections.indexOfFirst { it.groupValues[1].equals("SCRIPT", true) && it.range.first < 800 }
        val titleAt = if (scriptAt > 0) (0 until scriptAt).lastOrNull {
            sections[it].groupValues[1].equals("TITLE", true)
        } ?: -1 else -1
        if (titleAt >= 0) {
            fun contentAt(index: Int, end: Int): String {
                val match = sections[index]
                val inline = match.groupValues[2].trim()
                val rest = text.substring(match.range.last + 1, end).trim()
                return listOf(inline, rest).filter { it.isNotBlank() }.joinToString("\n").trim()
            }
            val rawTitle = contentAt(titleAt, sections[scriptAt].range.first)
            val title = rawTitle.lineSequence().firstOrNull()?.trim()?.removeSurrounding("**")?.trim()
            val tipAt = if (video) sections.indices.firstOrNull {
                it > scriptAt && sections[it].groupValues[1].replace(Regex("\\s+"), " ").equals("VIDEO TIP", true)
            } else null
            val scriptEnd = tipAt?.let { sections[it].range.first } ?: text.length
            val body = contentAt(scriptAt, scriptEnd)
            if (!title.isNullOrBlank() && title.length <= 100 && body.isNotBlank()) {
                val introAt = (0 until titleAt).lastOrNull { sections[it].groupValues[1].equals("INTRO", true) }
                val intro = introAt?.let { contentAt(it, sections[titleAt].range.first).take(300) }
                    ?.takeIf { it.isNotBlank() } ?: defaultIntro(video)
                val tip = tipAt?.let { contentAt(it, text.length).take(500) }?.takeIf { it.isNotBlank() }
                    ?: defaultTip(video)
                return Card(title, body, cleanCopy(body), intro, tip)
            }
        }
        val first = text.lineSequence().firstOrNull().orEmpty().trim()
        val labeled = heading.matchEntire(first)?.groupValues?.get(1)
            ?: titleLabel.matchEntire(first)?.groupValues?.get(1)
        val title = labeled?.trim()?.removeSurrounding("**")?.trim()?.takeIf { it.isNotBlank() && it.length <= 100 }
        val body = if (title != null) text.substringAfter('\n', "").trimStart('\n', '\r', ' ') else text
        val retained = body.ifBlank { text }
        // Legacy free-model replies stay complete; do not fabricate a video-specific tip for old text.
        return Card(title ?: "Story / Script", retained, cleanCopy(retained), defaultIntro(video))
    }

    private fun defaultIntro(video: Boolean) =
        if (video) "Yeh rahi tumhari video ke liye story aur ready-to-record script."
        else "Yeh rahi tumhari story."

    private fun defaultTip(video: Boolean): String? = if (video)
        "Voice-over record karo aur har paragraph ke liye ek matching shot chuno."
        else null

    /** Strip presentation markup only. Keep dialogue, paragraphs, pauses and scene directions. */
    internal fun cleanCopy(text: String): String = text.lines().joinToString("\n") { line ->
        line.replace(Regex("^\\s{0,3}#{1,3}\\s+"), "")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "$1")
    }.trim()

    const val WRITING_INSTRUCTIONS: String =
        "For an explicit creative story/script request, write an original, engaging piece in the " +
        "user's language and requested genre. The user wants a readable reply AROUND the copyable script, " +
        "not only the story. Follow this plain-text structure exactly (no code fences): " +
        "INTRO: One short, friendly sentence setting up the story, length or hook; no exaggerated promises. " +
        "TITLE: A concise original title. " +
        "SCRIPT: On the next line begin the complete story or ready-to-record video script. " +
        "Use short, atmospheric paragraphs and natural dialogue, with scene/voice-over cues only if useful. " +
        "Honor requested length and duration; finish the ending inside SCRIPT. " +
        "For video/reel/shorts requests ONLY, after the ending add VIDEO TIP: one concrete, brief " +
        "voice-over, shot, sound or editing instruction. Do not insert a tip inside SCRIPT. " +
        "Do not add apologies, unrelated advice, sales pitches or follow-up questions. " +
        "Never present invented fiction as a real event. Keep section names exactly as shown."
}
