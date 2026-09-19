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

    enum class Kind { STORY, VOICE_OVER, SCENE_SCRIPT }

    private val storyNoun = Regex("(?iu)(?:\\b(?:story|stories|kahani|kahaani|qissa|kissa)\\b|कहानी|کہانی|قصہ)")
    private val voiceOverNoun = Regex("(?iu)(?:\\b(?:voice[ -]?over|narration|narrate)\\b|वॉइस[ -]?ओवर|وائس[ -]?اوور)")
    private val sceneScriptNoun = Regex("(?iu)(?:\\b(?:script|screenplay|storyboard|scene[ -]by[ -]scene)\\b|स्क्रिप्ट|اسکرپٹ)")
    private val requestVerb = Regex("(?iu)(?:\\b(?:write|create|make|tell|generate|draft|compose|sunao|sunana|suna|likho|likh|banao|bana|give|do|de|dedo|dena|chahiye|chahie|sunaye)\\b|लिख|सुना|बना|سنا|لکھ)")
    private val explanation = Regex("(?iu)^(?:please\\s+)?(?:explain|define|summarize|analyse|analyze|review|critique|what is|how to|meaning of|definition of|क्या है|समझाओ)\\b")
    private val technicalScript = Regex("(?iu)\\b(?:python|javascript|bash|shell|automation|automate|terminal|function|script\\.js|code|coding|programming)\\b")
    private val videoNoun = Regex("(?iu)(?:\\b(?:video|reel|shorts|youtube|film|filming|shoot|recording)\\b|वीडियो|ویڈیو)")
    private val heading = Regex("^#{1,3}\\s+(.+?)\\s*#*\\s*$")
    private val titleLabel = Regex("(?i)^(?:\\*\\*)?(?:title|शीर्षक|عنوان)\\s*:\\s*(.+?)(?:\\*\\*)?\\s*$")
    // Section names are UI delimiters, not a reason to turn a requested STORY into a screenplay.
    private val section = Regex("(?im)^[ \\t]*(?:#{1,3}[ \\t]*)?(?:\\*\\*)?(INTRO|TITLE|STORY|SCRIPT|VOICE[ -]?OVER|VIDEO[ \\t]+TIP)(?:\\*\\*)?[ \\t]*:[ \\t]*([^\\n]*)$")

    /** The requested artifact wins over its destination: 'story for my video' is a STORY. */
    fun kind(prompt: String): Kind? {
        val text = prompt.trim().take(600)
        if (text.isEmpty() || explanation.containsMatchIn(text)) return null
        val story = storyNoun.containsMatchIn(text)
        val voiceOver = voiceOverNoun.containsMatchIn(text)
        val script = sceneScriptNoun.containsMatchIn(text)
        if (!story && !voiceOver && !script) return null
        if (script && technicalScript.containsMatchIn(text) && !story && !voiceOver) return null
        if (!requestVerb.containsMatchIn(text) && text.length > 90) return null
        return when {
            voiceOver -> Kind.VOICE_OVER
            script && !story -> Kind.SCENE_SCRIPT
            story -> Kind.STORY
            else -> Kind.SCENE_SCRIPT
        }
    }

    fun isWritingRequest(prompt: String): Boolean = kind(prompt) != null

    fun isVideoRequest(prompt: String): Boolean =
        videoNoun.containsMatchIn(prompt.take(600)) || voiceOverNoun.containsMatchIn(prompt.take(600))

    /** Each output kind gets its own instructions. Video is context, never a format override. */
    fun writingInstructions(prompt: String): String {
        val requested = kind(prompt) ?: return ""
        val common = "Write an original piece in the user's language, genre and requested length. " +
            "Answer the requested artifact, not a different production format. " +
            "Use the following plain-text labels on their own lines, without code fences. " +
            "INTRO: One brief, natural sentence outside the copyable content. " +
            "TITLE: A short original title outside the copyable content. "
        val content = when (requested) {
            Kind.STORY -> "STORY: Start the complete STORY on the next line. Write connected fictional " +
                "prose in natural paragraphs with a beginning, rising tension, an earned twist and an ending. " +
                "A STORY for a video is still a STORY, NOT a voice-over, shooting script or scene list. " +
                "Do NOT include [VOICEOVER], [SCENE], camera cues, stage directions, shot labels or " +
                "production notes in STORY. Do not turn the story into dialogue-only fragments. "
            Kind.VOICE_OVER -> "VOICEOVER: On the next line provide only the words to be spoken " +
                "aloud, with natural pacing and a complete ending. Do not add [SCENE], camera or " +
                "editing directions to the spoken text. Only produce voice-over because it was requested. "
            Kind.SCENE_SCRIPT -> "SCRIPT: On the next line write a complete script. Scene headings, " +
                "action, dialogue and visual cues are appropriate here because a script was requested. " +
                "Do not turn an unrelated story request into this format. "
        }
        val tip = if (isVideoRequest(prompt))
            "After the complete content and its ending, add VIDEO TIP: one short, specific, useful " +
                "filming, sound or editing suggestion outside the copyable content. "
        else "Do not add VIDEO TIP unless the user requests video or voice-over context. "
        return common + content + tip +
            "Keep INTRO, TITLE and any VIDEO TIP outside the main content. " +
            "Do not add unrelated advice, sales pitches or follow-up questions. " +
            "Never present invented fiction as a real event."
    }

    /** Fall back to the original full piece if a free model omits labels; never discard prose. */
    fun card(prompt: String, reply: String): Card? {
        val requested = kind(prompt) ?: return null
        var text = reply.trim().replace("\r\n", "\n")
        if (text.isEmpty()) return null
        val fenced = Regex("(?s)^```(?:markdown|text|story|script)?\\s*\\n(.*?)\\n```$").matchEntire(text)
        if (fenced != null) text = fenced.groupValues[1].trim()
        val video = isVideoRequest(prompt)
        val sections = section.findAll(text).toList()
        val bodyAt = sections.indexOfFirst {
            it.range.first < 800 && it.groupValues[1].uppercase().replace("-", "").replace(" ", "") in
                setOf("STORY", "SCRIPT", "VOICEOVER")
        }
        val titleAt = if (bodyAt > 0) (0 until bodyAt).lastOrNull {
            sections[it].groupValues[1].equals("TITLE", true)
        } ?: -1 else -1
        if (titleAt >= 0) {
            fun contentAt(index: Int, end: Int): String {
                val match = sections[index]
                val inline = match.groupValues[2].trim()
                val rest = text.substring(match.range.last + 1, end).trim()
                return listOf(inline, rest).filter { it.isNotBlank() }.joinToString("\n").trim()
            }
            val rawTitle = contentAt(titleAt, sections[bodyAt].range.first)
            val title = rawTitle.lineSequence().firstOrNull()?.trim()?.removeSurrounding("**")?.trim()
            val tipAt = if (video) sections.indices.firstOrNull {
                it > bodyAt && sections[it].groupValues[1].replace(Regex("\\s+"), " ").equals("VIDEO TIP", true)
            } else null
            val body = contentAt(bodyAt, tipAt?.let { sections[it].range.first } ?: text.length)
            if (!title.isNullOrBlank() && title.length <= 100 && body.isNotBlank()) {
                val introAt = (0 until titleAt).lastOrNull { sections[it].groupValues[1].equals("INTRO", true) }
                val intro = introAt?.let { contentAt(it, sections[titleAt].range.first).take(300) }
                    ?.takeIf { it.isNotBlank() } ?: defaultIntro(video, requested)
                val tip = tipAt?.let { contentAt(it, text.length).take(500) }?.takeIf { it.isNotBlank() }
                    ?: defaultTip(video, requested)
                return Card(title, body, cleanCopy(body), intro, tip)
            }
        }
        val first = text.lineSequence().firstOrNull().orEmpty().trim()
        val labeled = heading.matchEntire(first)?.groupValues?.get(1)
            ?: titleLabel.matchEntire(first)?.groupValues?.get(1)
        val title = labeled?.trim()?.removeSurrounding("**")?.trim()?.takeIf { it.isNotBlank() && it.length <= 100 }
        val body = if (title != null) text.substringAfter('\n', "").trimStart('\n', '\r', ' ') else text
        val retained = body.ifBlank { text }
        // A free model may ignore the requested artifact; do not silently delete its content.
        return Card(title ?: "Story / Script", retained, cleanCopy(retained),
            defaultIntro(video, requested), defaultTip(video, requested))
    }

    private fun defaultIntro(video: Boolean, kind: Kind): String = when (kind) {
        Kind.STORY -> if (video) "Yeh rahi tumhari video ke liye kahani." else "Yeh rahi tumhari kahani."
        Kind.VOICE_OVER -> "Yeh raha tumhara voice-over text."
        Kind.SCENE_SCRIPT -> if (video) "Yeh rahi tumhari video script." else "Yeh raha tumhara script."
    }

    private fun defaultTip(video: Boolean, kind: Kind): String? = if (!video) null else when (kind) {
        Kind.STORY -> "Kahani ke suspense moments par visuals dheere badlo aur ending par chhota pause rakho."
        Kind.VOICE_OVER -> "Narration ko ek baar record karke pauses aur sound levels check karo."
        Kind.SCENE_SCRIPT -> "Har scene ke liye matching shot aur transition plan karo."
    }

    /** Strip presentation markup only. Keep dialogue, paragraphs, pauses and scene directions. */
    internal fun cleanCopy(text: String): String = text.lines().joinToString("\n") { line ->
        line.replace(Regex("^\\s{0,3}#{1,3}\\s+"), "")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "$1")
    }.trim()
}
