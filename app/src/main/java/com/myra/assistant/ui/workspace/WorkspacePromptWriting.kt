package com.myra.assistant.ui.workspace

/** Writing-only intent. This does not start coding or access project source. */
internal object WorkspacePromptWriting {
    enum class Kind { BUILD, PERSONA }

    private val promptWord = Regex("(?iu)\\bprompts?\\b|प्रॉम्प्ट|پرومپٹ")
    private val explanation = Regex("(?iu)^(?:please\\s+)?(?:what is|how to|explain|define|meaning of|difference between|क्या है|समझाओ)\\b")
    private val target = Regex("(?iu)\\b(?:companion|assistant|chatbot|agent|bot|app|application|website|software|project|platform)\\b|ऐप|एप्लिकेशन|सहायक")
    private val build = Regex("(?iu)\\b(?:banane|banana|banwana|banao|bana|banani|build|building|develop|development|implement|create|creating|coding|code|make|making)\\b|बनाने|बनाना|बनाओ|बनवाने|تیار")
    private val explicitCoding = Regex("(?iu)\\b(?:coding|developer|development|implementation|engineering)\\s+prompts?\\b|\\bprompts?\\s+(?:for|to)\\s+(?:build|develop|code|implement)\\b")
    private val persona = Regex("(?iu)\\b(?:personality|roleplay|character|behavio[u]?r|custom instructions|system prompts?)\\b|पर्सनैलिटी|किरदार")
    private val systemPrompt = Regex("(?iu)\\bsystem\\s+prompts?\\b")
    private val concreteBuild = Regex("(?iu)\\b(?:banane|banana|banwana|banao|banani|build|building|develop|implement|coding|code|application|website)\\b|बनाने|बनाना|बनाओ")
    private val section = Regex("(?im)^[ \\t]*(?:#{1,3}[ \\t]*)?(?:\\*\\*)?(INTRO|TITLE|PROMPT|NEXT[ \\t]+STEP)(?:\\*\\*)?[ \\t]*:[ \\t]*([^\\n]*)$")

    fun kind(message: String): Kind? {
        val text = message.trim().take(600)
        if (!promptWord.containsMatchIn(text) || explanation.containsMatchIn(text)) return null
        if (persona.containsMatchIn(text) && !concreteBuild.containsMatchIn(text)) return Kind.PERSONA
        if (explicitCoding.containsMatchIn(text) || (target.containsMatchIn(text) && build.containsMatchIn(text))) {
            return Kind.BUILD
        }
        if (systemPrompt.containsMatchIn(text) || persona.containsMatchIn(text)) return Kind.PERSONA
        return null // Do not hijack ordinary chat or unrelated prompts.
    }

    fun instructions(message: String): String {
        val mode = kind(message) ?: return ""
        val shared = "The user wants a COPYABLE PROMPT, not a claim that you have built anything. " +
            "Use only details in the current request and the provided conversation; do not invent a " +
            "project name, platform, repository, implemented feature or remembered preference. " +
            "Follow this exact plain-text envelope with labels on separate lines and no outer code fence: " +
            "INTRO: one concise sentence about what this prompt is for. " +
            "TITLE: a specific, short title. " +
            "PROMPT: start the complete reusable prompt on the next line. " +
            "NEXT STEP: one brief actionable instruction OUTSIDE the prompt. " +
            "Keep all content the user will paste into another AI inside PROMPT only. " +
            "Match the user's language outside the prompt, and use the requested language inside it. "
        return shared + when (mode) {
            Kind.BUILD -> "The user wants a prompt for a CODING/DEVELOPMENT AI to BUILD the described " +
                "product, not a role-play or personality-only system prompt. For example, 'AI companion " +
                "banane ke liye prompt' means a development brief, not 'You are CareCompanion'. " +
                "Write a concrete, usable engineering brief: goal and audience; requested user-facing " +
                "behavior; key functions; appropriate data, memory and privacy boundaries when relevant; " +
                "technical constraints and integration with EXISTING code if any; implementation stages; " +
                "and observable tests/acceptance criteria. Do not invent a platform when unspecified: " +
                "ask the coding AI to confirm it before platform-specific implementation. If the user " +
                "asks for a short prompt, keep it short. No unrequested fictional personality, random " +
                "bot name, platform-directory table or generic tips. Clearly separate proposed features " +
                "from verified working functionality; do not promise permanent memory from wording alone. " +
                "Do not claim a build works on a phone without device testing."
            Kind.PERSONA -> "The user explicitly wants a personality/system prompt for an AI character. " +
                "Provide behavior, tone and boundaries inside PROMPT, not a software-build plan. " +
                "Do not claim memory, voice, tools or emotional experience that the host system has " +
                "not implemented; don't say the bot is always available or a human replacement. " +
                "Keep it practical rather than a long list of generic flattery."
        }
    }

    /**
     * A follow-up such as 'isme hands-free add karo' has no word 'prompt'. The provider's
     * structured PROMPT reply is still the same writing artifact and must stay copyable.
     * Neither the user's nor provider's stored text is rewritten.
     */
    fun card(message: String, reply: String): WorkspaceStoryScript.Card? {
        val mode = kind(message)
        val text = reply.trim().replace("\r\n", "\n")
        if (text.isBlank()) return null
        val structuredRevision = mode == null && WorkspacePromptFollowUp.looksLikeRevision(message) &&
            WorkspacePromptFollowUp.hasPromptLabel(text)
        if (mode == null && !structuredRevision) return null
        val matches = section.findAll(text).toList()
        val bodyAt = matches.indexOfFirst {
            it.range.first < 800 && it.groupValues[1].equals("PROMPT", ignoreCase = true)
        }
        val titleAt = if (bodyAt > 0) (0 until bodyAt).lastOrNull {
            matches[it].groupValues[1].equals("TITLE", ignoreCase = true)
        } ?: -1 else -1
        fun content(index: Int, end: Int): String {
            val found = matches[index]
            return listOf(found.groupValues[2].trim(), text.substring(found.range.last + 1, end).trim())
                .filter { it.isNotBlank() }.joinToString("\n").trim()
        }
        if (titleAt >= 0) {
            val title = content(titleAt, matches[bodyAt].range.first)
                .lineSequence().firstOrNull()?.trim()?.removeSurrounding("**")?.trim()
            val nextAt = matches.indices.firstOrNull { i ->
                i > bodyAt && matches[i].groupValues[1].replace(Regex("\\s+"), " ")
                    .equals("NEXT STEP", ignoreCase = true)
            }
            val body = content(bodyAt, nextAt?.let { matches[it].range.first } ?: text.length)
            if (!title.isNullOrBlank() && title.length <= 100 && body.isNotBlank()) {
                val introAt = (0 until titleAt).lastOrNull {
                    matches[it].groupValues[1].equals("INTRO", ignoreCase = true)
                }
                val intro = introAt?.let { content(it, matches[titleAt].range.first).take(300) }
                    ?.takeIf { it.isNotBlank() }.orEmpty()
                val next = nextAt?.let { content(it, text.length).take(500) }?.takeIf { it.isNotBlank() }
                return WorkspaceStoryScript.Card(title, body, body, intro, next, promptCard = true)
            }
        }
        // If a free model supplies PROMPT but omits TITLE, retain its actual prompt body.
        // Only make this limited recovery for a clearly referential follow-up.
        if (structuredRevision && bodyAt >= 0) {
            val nextAt = matches.indices.firstOrNull { i ->
                i > bodyAt && matches[i].groupValues[1].replace(Regex("\\s+"), " ")
                    .equals("NEXT STEP", ignoreCase = true)
            }
            val body = content(bodyAt, nextAt?.let { matches[it].range.first } ?: text.length)
            if (body.isNotBlank()) {
                val intro = text.substring(0, matches[bodyAt].range.first).trim().take(300)
                val next = nextAt?.let { content(it, text.length).take(500) }
                return WorkspaceStoryScript.Card("Updated prompt", body, body, intro, next, promptCard = true)
            }
        }
        // The model may ignore markers. Never silently replace or delete its actual answer.
        return mode?.let {
            WorkspaceStoryScript.Card(
                if (it == Kind.BUILD) "Development prompt" else "Personality prompt",
                text, text, promptCard = true)
        }
    }
}
