package com.myra.assistant.data.memory

import java.util.Locale

/**
 * Extracts only explicit, concrete, low-risk preferences from completed user turns.
 * Automatic memory must prefer missing a fact over saving a wrong inference.
 */
object AutomaticMemoryExtractor {
    private val prohibitedOrPersonal = Regex(
        """\b(?:otp|passwords?|passcode|pin|cvv|security\s*code|verification\s*code|recovery\s*code|authentication\s*token|auth\s*token|api\s*key|private\s*key|seed\s*phrase|bank|account|address|health|disease|diagnosis|religion|sexual|trauma|fear|afraid|friend|dost|girlfriend|boyfriend|wife|husband|mother|father|brother|sister|relationship|age|years?\s+old|saal)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ambiguousSubject = Regex(
        """^(?:it|this|that|these|those|ye|yeh|vo|woh|wo|isko|usko|ise|use|something|kuch)$""",
        RegexOption.IGNORE_CASE
    )
    private val negation = Regex(
        """\b(?:do\s+not|don't|dont|not|never|na|nahi|nahin|pasand\s+nahi)\b""",
        RegexOption.IGNORE_CASE
    )

    fun extract(raw: String): MemoryCandidate? {
        val text = raw.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (text.length !in 4..160 || negation.containsMatchIn(text) ||
            prohibitedOrPersonal.containsMatchIn(text)
        ) return null

        communicationStyle(text)?.let { (slot, value) ->
            return durableCandidate(
                MemoryCategory.COMMUNICATION_STYLE,
                "Zopy prefers $value",
                if (slot == "response_style") RESPONSE_STYLE_KEY else "communication:$slot",
                0.95
            )
        }
        appUsage(text)?.let { (task, app) ->
            return durableCandidate(
                MemoryCategory.APP_USAGE,
                "Zopy usually uses $app for $task",
                "app_usage:${normalize(task)}",
                0.94
            )
        }
        successfulSolution(text)?.let { (problem, solution) ->
            return durableCandidate(
                MemoryCategory.SOLUTION,
                "$solution worked for Zopy's $problem",
                "solution:${normalize(problem)}",
                0.91
            )
        }
        englishPreference(text)?.let { subject -> return preference(subject) }
        hinglishPreference(text)?.let { subject -> return preference(subject) }
        favoritePreference(text)?.let { (kind, value) ->
            val cleanKind = cleanSubject(kind) ?: return null
            val cleanValue = cleanSubject(value) ?: return null
            return MemoryCandidate(
                category = MemoryCategory.PREFERENCE,
                fact = "Zopy's favorite $cleanKind is $cleanValue",
                stableKey = "preference:favorite:${normalize(cleanKind)}",
                sensitivity = MemorySensitivity.LOW,
                confidence = 0.95,
                source = "automatic_conversation"
            )
        }
        workflow(text)?.let { (task, value) ->
            return durableCandidate(
                MemoryCategory.WORKFLOW,
                "Zopy usually $value",
                "workflow:${normalize(task)}",
                0.92
            )
        }
        return null
    }

    private fun communicationStyle(text: String): Pair<String, String>? {
        Regex(
            """^(?:please\s+)?(?:give|keep|make)\s+(?:me\s+)?(?:your\s+)?(short|concise|brief|detailed|step[ -]by[ -]step|simple)\s+(?:answer|answers|reply|replies|response|responses|instructions)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { match ->
            val style = match.groupValues[1].lowercase(Locale.ROOT).replace('-', ' ')
            return "response_style" to "$style answers"
        }
        Regex(
            """^(?:please\s+)?keep\s+(?:the\s+)?(?:answer|answers|reply|replies|response|responses)\s+(short|concise|brief|detailed)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { match ->
            return "response_style" to "${match.groupValues[1].lowercase(Locale.ROOT)} answers"
        }
        Regex(
            """^i\s+(?:prefer|want)\s+(?:you\s+to\s+answer\s+in|answers?\s+in)\s+(english|hindi|hinglish)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { return "language" to "answers in ${it.groupValues[1]}" }
        Regex(
            """^(?:please\s+)?(?:be|stay)\s+(concise|brief|detailed)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { match ->
            val value = if (match.groupValues[1].equals("detailed", true)) "detailed" else "short"
            return "response_style" to "$value answers"
        }
        Regex(
            """^(?:please\s+)?(?:give(?:\s+me)?\s+)?(?:longer|more\s+detailed)\s+explanations$|^explain\s+(?:things|it)\s+in\s+(?:more\s+)?detail$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { return "response_style" to "detailed answers" }
        return null
    }

    private fun workflow(text: String): Pair<String, String>? =
        Regex(
            """^i\s+(?:always|usually)\s+(.+?)\s+(?:when|for)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { match ->
            val action = cleanSubject(match.groupValues[1]) ?: return@let null
            val task = cleanSubject(match.groupValues[2]) ?: return@let null
            task to "$action for $task"
        }

    private fun appUsage(text: String): Pair<String, String>? =
        Regex(
            """^i\s+(?:always|usually|normally)\s+use\s+([\p{L}\p{N} .+_-]{2,40})\s+for\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { match ->
            val app = cleanSubject(match.groupValues[1]) ?: return@let null
            val task = cleanSubject(match.groupValues[2]) ?: return@let null
            task to app
        }

    private fun successfulSolution(text: String): Pair<String, String>? {
        Regex(
            """^(.+?)\s+(?:worked|works)\s+for\s+me\s+(?:for|with)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { match ->
            val solution = cleanSubject(match.groupValues[1]) ?: return@let null
            val problem = cleanSubject(match.groupValues[2]) ?: return@let null
            return problem to solution
        }
        Regex(
            """^(?:this|that)\s+(?:fix|solution)\s+worked\s+for\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { match ->
            val problem = cleanSubject(match.groupValues[1]) ?: return@let null
            return problem to "The confirmed solution"
        }
        return null
    }

    private fun durableCandidate(
        category: MemoryCategory,
        fact: String,
        stableKey: String,
        confidence: Double
    ) = MemoryCandidate(
        category = category,
        fact = fact,
        stableKey = stableKey,
        sensitivity = MemorySensitivity.LOW,
        confidence = confidence,
        source = "automatic_conversation"
    )

    private fun englishPreference(text: String): String? =
        Regex(
            """^(?:i|i\s+(?:really|always))\s+(?:like|love|prefer|enjoy)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.groupValues?.get(1)

    private fun hinglishPreference(text: String): String? =
        listOf(
            Regex(
                """^mujhe\s+(.+?)\s+(?:(?:bahut|bahuta|bohot|bohat|kaafi)\s+)?pasand[ae]?\s+(?:hai|hain|he)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^main\s+(.+?)\s+(?:pasand\s+karta|pasand\s+karti)\s+(?:hun|hoon|hu)$""",
                RegexOption.IGNORE_CASE
            )
        ).firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) }

    private fun favoritePreference(text: String): Pair<String, String>? =
        Regex(
            """^(?:my|mera|meri)\s+(?:favorite|favourite|favret|pasandida)\s+([\p{L}\p{N} ]{2,30}?)\s+(?:is|hai|he)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { it.groupValues[1] to it.groupValues[2] }

    private fun preference(rawSubject: String): MemoryCandidate? {
        val subject = cleanSubject(rawSubject)?.let(::canonicalSubject) ?: return null
        val responseStyle = Regex(
            """^(?:short|concise|brief|detailed|long)\s+(?:answer|answers|reply|replies|response|responses)$""",
            RegexOption.IGNORE_CASE
        ).matches(subject)
        return MemoryCandidate(
            category = MemoryCategory.PREFERENCE,
            fact = if (responseStyle) "Zopy prefers $subject" else "Zopy likes $subject",
            stableKey = if (responseStyle) RESPONSE_STYLE_KEY else "preference:likes:${normalize(subject)}",
            sensitivity = MemorySensitivity.LOW,
            confidence = 0.93,
            source = "automatic_conversation"
        )
    }

    private fun cleanSubject(value: String): String? {
        val clean = value.trim().trim('"', '\'', '.', ',', '!', '?')
            .replace(Regex("\\s+"), " ")
        val words = clean.split(' ').filter(String::isNotBlank)
        if (clean.length !in 2..80 || words.size !in 1..12 ||
            ambiguousSubject.matches(clean) || prohibitedOrPersonal.containsMatchIn(clean)
        ) return null
        return clean
    }

    private fun canonicalSubject(value: String): String {
        val normalized = normalize(value)
        return when {
            Regex("(?:science|sainsa|sains)(?:\\s+(?:science|sainsa|sains))?\\s+(?:fiction|phiksana|phiksan).*?(?:movie|muvi|muvija)").containsMatchIn(normalized) ->
                "science-fiction movies"
            Regex("(?:horror|horara).*?(?:movie|muvi|muvija)").containsMatchIn(normalized) ->
                "horror movies"
            Regex("^(?:ghumana|ghoomana|gumāna|ghumna)$").matches(normalized) -> "ghumna"
            else -> value
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private const val RESPONSE_STYLE_KEY = PreferenceMemoryIdentity.RESPONSE_VERBOSITY_KEY
}
