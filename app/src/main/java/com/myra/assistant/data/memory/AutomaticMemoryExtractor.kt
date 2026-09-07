package com.myra.assistant.data.memory

import java.util.Locale

object AutomaticMemoryExtractor {
    private val prohibitedOrPersonal = Regex(
        """\b(?:otp|passwords?|passcode|pin|cvv|security\s*code|verification\s*code|recovery\s*code|authentication\s*token|auth\s*token|api\s*key|private\s*key|seed\s*phrase|bank|account|address|health|disease|diagnosis|religion|sexual|trauma|fear|afraid|friend|dost|girlfriend|boyfriend|wife|husband|mother|father|brother|sister|relationship|age|years?\s+old|saal)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ambiguousSubject = Regex(
        """^(?:it|this|that|these|those|ye|yeh|vo|woh|wo|isko|usko|ise|use|something|kuch|same|wahi)$""",
        RegexOption.IGNORE_CASE
    )
    private val uncertain = Regex(
        """\b(?:shayad|maybe|probably|perhaps|possibly|guess|i\s+think|i\s+guess|i\s+suppose|ho\s+sakta|ho\s+sakti|ho\s+sakte)\b|\b(?:mujhe|mereko|merko|main|mai)\s+(?:aisa\s+)?lagta\s+(?:hai|he)(?:\s+ki)?\b|\b(?:pasand|like|love|enjoy).{0,30}\b(?:hogi|hoga|honge|might|could)\b""",
        RegexOption.IGNORE_CASE
    )
    private val temporary = Regex(
        """\b(?:aaj|today|abhi|currently|filhaal|filhal|right\s+now|is\s+waqt|iss\s+waqt|tonight|for\s+now)\b""",
        RegexOption.IGNORE_CASE
    )
    private val questionStart = Regex(
        """^(?:kya|kaun|kaunsa|kaunsi|kiska|kis|kab|what|who|which|when|where|do\s+you|does\s+he|does\s+she|is\s+it)\b""",
        RegexOption.IGNORE_CASE
    )
    private val questionEnd = Regex("""\b(?:kya|right|hai\s+na|he\s+na)$""", RegexOption.IGNORE_CASE)

    private data class PersonPreference(val name: String, val subject: String, val likes: Boolean)

    fun extract(raw: String): MemoryCandidate? {
        val rawTrimmed = raw.trim()
        val text = rawTrimmed.trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (text.length !in 4..180 || rawTrimmed.endsWith('?') || questionStart.containsMatchIn(text) ||
            questionEnd.containsMatchIn(text) || uncertain.containsMatchIn(text) || temporary.containsMatchIn(text) ||
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
        namedPersonPreference(text)?.let { value -> return personPreference(value) }
        negativeSelfPreference(text)?.let { subject -> return preference(subject, likes = false) }
        englishPreference(text)?.let { subject -> return preference(subject) }
        hinglishPreference(text)?.let { subject -> return preference(subject) }
        colloquialPreference(text)?.let { subject -> return preference(subject) }
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
        listOf(
            Regex("""^i\s+(?:(?:really|always)\s+)?(?:like|love|prefer|enjoy)\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^i(?:'m|\s+am)\s+into\s+(.+)$""", RegexOption.IGNORE_CASE),
            Regex("""^(.+?)\s+is\s+fun\s+for\s+me$""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) }

    private fun hinglishPreference(text: String): String? =
        listOf(
            Regex(
                """^(?:mujhe|mereko|merko)(?:\s+na)?\s+(.+?)\s+(?:(?:bahut|bahuta|bohot|bohat|kaafi)\s+)?pasand[ae]?\s+(?:hai|hain|he)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^(?:main|mai|mein)\s+(.+?)\s+(?:pasand\s+karta|pasand\s+karti)\s+(?:hun|hoon|hu)$""",
                RegexOption.IGNORE_CASE
            )
        ).firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) }

    private fun colloquialPreference(text: String): String? =
        listOf(
            Regex(
                """^(?:mujhe|mereko|merko)(?:\s+na)?\s+(.+?)\s+(?:accha|achha|acha|aacha|achcha|acchi|achhi|achi)\s+lag(?:ta|ti|ata|ati)\s+(?:hai|he)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^(.+?)\s+(?:mein|me)\s+(?:maza|mazza)\s+(?:aata|ata)\s+(?:hai|he)\s+(?:mujhe|mereko|merko)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^(?:mujhe|mereko|merko)(?:\s+na)?\s+(.+?)\s+(?:mein|me)\s+(?:maza|mazza)\s+(?:aata|ata)\s+(?:hai|he)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^(?:main|mai|mein)\s+(.+?)\s+(?:enjoy\s+karta|enjoy\s+karti)\s+(?:hun|hoon|hu)$""",
                RegexOption.IGNORE_CASE
            )
        ).firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) }

    private fun negativeSelfPreference(text: String): String? =
        listOf(
            Regex(
                """^i\s+(?:(?:do\s+not|don't|dont)\s+(?:like|love|enjoy)|no\s+longer\s+(?:like|love|enjoy))\s+(.+?)(?:\s+anymore)?$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^(?:mujhe|mereko|merko)(?:\s+na)?\s+(.+?)\s+(?:ab\s+)?pasand[ae]?\s+nahi\s+(?:hai|hain|he)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^(?:mujhe|mereko|merko)(?:\s+na)?\s+(.+?)\s+(?:accha|achha|acha|aacha|achcha|acchi|achhi|achi)\s+nahi\s+lag(?:ta|ti|ata|ati)\s+(?:hai|he)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^(.+?)\s+(?:mujhe|mereko|merko)\s+(?:(?:utna|itna|zyada|jyada)\s+)?pasand[ae]?\s+nahi\s+(?:hai|he)$""",
                RegexOption.IGNORE_CASE
            )
        ).firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) }

    private fun namedPersonPreference(text: String): PersonPreference? {
        val namePattern = "([\\p{L}][\\p{L}'-]{1,29}(?:\\s+[\\p{L}][\\p{L}'-]{1,29}){0,2})"
        val positive = listOf(
            Regex("^$namePattern\\s+ko(?:\\s+bhi)?\\s+(.+?)(?:\\s+bhi)?\\s+pasand[ae]?\\s+(?:hai|hain|he)$", RegexOption.IGNORE_CASE),
            Regex("^$namePattern\\s+ko(?:\\s+bhi)?\\s+(.+?)(?:\\s+bhi)?\\s+(?:accha|achha|acha|aacha|achcha|acchi|achhi|achi)\\s+lag(?:ta|ti|ata|ati)\\s+(?:hai|he)$", RegexOption.IGNORE_CASE),
            Regex("^$namePattern\\s+(.+?)\\s+(?:pasand\\s+karta|pasand\\s+karti|enjoy\\s+karta|enjoy\\s+karti)\\s+(?:hai|he)$", RegexOption.IGNORE_CASE),
            Regex("^$namePattern\\s+(?:likes|loves|enjoys|prefers)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        positive.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(text)?.let { match ->
                PersonPreference(match.groupValues[1], match.groupValues[2], true)
            }
        }?.let { return it }

        val negative = listOf(
            Regex("^$namePattern\\s+ko(?:\\s+bhi)?\\s+(.+?)(?:\\s+(?:utna|itna|zyada|jyada))?\\s+pasand[ae]?\\s+nahi\\s+(?:hai|he)$", RegexOption.IGNORE_CASE),
            Regex("^$namePattern\\s+ko(?:\\s+bhi)?\\s+(.+?)\\s+(?:accha|achha|acha|aacha|achcha|acchi|achhi|achi)\\s+nahi\\s+lag(?:ta|ti|ata|ati)\\s+(?:hai|he)$", RegexOption.IGNORE_CASE),
            Regex("^$namePattern\\s+(?:does\\s+not|doesn't|doesnt)\\s+(?:like|love|enjoy)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        return negative.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(text)?.let { match ->
                PersonPreference(match.groupValues[1], match.groupValues[2], false)
            }
        }
    }

    private fun personPreference(value: PersonPreference): MemoryCandidate? {
        val name = cleanPersonName(value.name) ?: return null
        val subject = cleanSubject(value.subject)?.let(::canonicalSubject) ?: return null
        val keySubject = normalize(subject)
        return MemoryCandidate(
            category = MemoryCategory.PERSON,
            fact = if (value.likes) "$name likes $subject" else "$name does not like $subject",
            stableKey = "person:${personToken(name)}:preference:$keySubject",
            sensitivity = MemorySensitivity.PERSONAL,
            confidence = 0.94,
            source = "automatic_conversation",
            entityId = NaturalMemoryExtractor.stablePersonId(name),
            entityName = name
        )
    }

    private fun favoritePreference(text: String): Pair<String, String>? =
        Regex(
            """^(?:my|mera|meri)\s+(?:favorite|favourite|favret|pasandida)\s+([\p{L}\p{N} ]{2,30}?)\s+(?:is|hai|he)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(text)?.let { it.groupValues[1] to it.groupValues[2] }

    private fun preference(rawSubject: String, likes: Boolean = true): MemoryCandidate? {
        val subject = cleanSubject(rawSubject)?.let(::canonicalSubject) ?: return null
        val responseStyle = Regex(
            """^(?:short|concise|brief|detailed|long)\s+(?:answer|answers|reply|replies|response|responses)$""",
            RegexOption.IGNORE_CASE
        ).matches(subject)
        return MemoryCandidate(
            category = MemoryCategory.PREFERENCE,
            fact = when {
                responseStyle -> "Zopy prefers $subject"
                likes -> "Zopy likes $subject"
                else -> "Zopy does not like $subject"
            },
            stableKey = if (responseStyle) RESPONSE_STYLE_KEY else "preference:likes:${normalize(subject)}",
            sensitivity = MemorySensitivity.LOW,
            confidence = if (likes) 0.93 else 0.94,
            source = "automatic_conversation"
        )
    }

    private fun cleanSubject(value: String): String? {
        val clean = value.trim().trim('"', '\'', '.', ',', '!', '?')
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^(?:na|toh|to|actually)\\s+", RegexOption.IGNORE_CASE), "")
        val words = clean.split(' ').filter(String::isNotBlank)
        if (clean.length !in 2..80 || words.size !in 1..12 ||
            ambiguousSubject.matches(clean) || prohibitedOrPersonal.containsMatchIn(clean) ||
            uncertain.containsMatchIn(clean)
        ) return null
        return clean
    }

    private fun cleanPersonName(value: String): String? {
        val clean = value.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 2..60 || clean.split(' ').size !in 1..3) return null
        if (clean.lowercase(Locale.ROOT) in setOf("main", "mai", "mein", "mujhe", "mereko", "merko", "i", "me", "you", "tum", "vo", "woh", "wo")) return null
        return clean.split(' ').joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
        }
    }

    private fun canonicalSubject(value: String): String {
        val clean = value.trim().replace(Regex("\\s+"), " ")
        val normalized = normalize(clean)
        return when {
            Regex("^(?:code|codes|coding)(?:\\s+(?:karna|karne|karni))?$").matches(normalized) -> "coding"
            Regex("(?:science|sainsa|sains)(?:\\s+(?:science|sainsa|sains))?\\s+(?:fiction|phiksana|phiksan).*?(?:movie|muvi|muvija)").containsMatchIn(normalized) ->
                "science-fiction movies"
            Regex("(?:horror|horara).*?(?:movie|muvi|muvija)").containsMatchIn(normalized) ->
                "horror movies"
            Regex("^(?:ghumana|ghoomana|gumāna|ghumna)$").matches(normalized) -> "ghumna"
            Regex("^(.+?)\\s+(?:karna|karne|karni)$", RegexOption.IGNORE_CASE).matches(clean) ->
                clean.replace(Regex("\\s+(?:karna|karne|karni)$", RegexOption.IGNORE_CASE), "").trim()
            else -> clean
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun personToken(value: String): String = normalize(value).replace(' ', '_').take(36)

    private const val RESPONSE_STYLE_KEY = PreferenceMemoryIdentity.RESPONSE_VERBOSITY_KEY
}
