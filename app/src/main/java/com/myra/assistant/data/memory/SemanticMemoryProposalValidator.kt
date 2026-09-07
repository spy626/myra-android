package com.myra.assistant.data.memory

import java.util.Locale

object SemanticMemoryProposalValidator {
    private val safeKey = Regex("[a-z0-9][a-z0-9:_-]{1,49}")
    private val prohibited = Regex(
        """\b(?:otp|passwords?|passcode|pin|cvv|security code|verification code|recovery code|authentication token|auth token|api key|private key|seed phrase|bank|account number|card number|aadhaar|aadhar|pan number|passport number)\b""",
        RegexOption.IGNORE_CASE
    )
    private val sensitive = Regex(
        """\b(?:address|bank|account number|diagnosis|disease|medical|religion|sexual|trauma|fear|afraid)\b""",
        RegexOption.IGNORE_CASE
    )
    private val malformedFact = Regex(
        """\b(?:zopy|tum|user)\s+likes\s+to\b|\bre-?visit\s+travel\s+destinations?\b""",
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
    private val positivePreference = Regex(
        """\b(?:like|likes|liked|love|loves|prefer|prefers|favorite|favourite|enjoy|enjoys|pasand)\b|\b(?:accha|achha|acha|aacha|achcha|acchi|achhi|achi)\s+lag(?:ta|ti|ata|ati)\b|\b(?:maza|mazza)\s+(?:aata|ata)\b|\bi(?:'m|\s+am)\s+into\b""",
        RegexOption.IGNORE_CASE
    )
    private val negativePreference = Regex(
        """\b(?:do\s+not|don't|dont|does\s+not|doesn't|doesnt|not)\s+(?:like|love|enjoy|prefer)\b|\bpasand[ae]?\s+nahi\b|\b(?:accha|achha|acha|aacha|achcha|acchi|achhi|achi)\s+nahi\s+lag(?:ta|ti|ata|ati)\b|\bno\s+longer\s+(?:like|love|enjoy|prefer)\b""",
        RegexOption.IGNORE_CASE
    )
    private val selfPreference = Regex(
        """\b(?:i\s+(?:(?:do\s+not|don't|dont|no\s+longer)\s+)?(?:like|love|prefer|enjoy)|i(?:'m|\s+am)\s+into|mujhe|mereko|merko|main\s+.+?\s+(?:pasand|enjoy))\b""",
        RegexOption.IGNORE_CASE
    )
    private val contextualPersonPronoun = Regex(
        """\b(?:usko|use|uska|uski|uske|unka|unki|unke|him|her|that\s+person|that\s+friend)\b""",
        RegexOption.IGNORE_CASE
    )
    private val stopWords = setOf(
        "a", "an", "the", "is", "are", "am", "was", "were", "to", "of", "and", "or",
        "my", "meri", "mera", "mere", "hai", "hain", "he", "hoon", "hun", "ka", "ki", "ke",
        "ko", "me", "mein", "zopy", "zopy's", "user", "it", "that", "this", "woh", "vo", "uska", "iska",
        "mujhe", "mereko", "merko", "main", "mai", "na", "bhi", "bahut", "bohot", "kaafi"
    )

    private data class PreferenceFact(
        val personName: String?,
        val subject: String,
        val positive: Boolean
    )

    fun validate(
        fact: String,
        categoryName: String,
        memoryKey: String,
        evidence: String,
        confidence: Double,
        conversationContext: String
    ): MemoryCandidate? {
        val cleanFact = fact.trim().replace(Regex("\\s+"), " ")
        val cleanEvidence = evidence.trim().replace(Regex("\\s+"), " ")
        val category = runCatching {
            MemoryCategory.valueOf(categoryName.trim().uppercase(Locale.ROOT))
        }.getOrNull() ?: return null
        val key = normalize(memoryKey).replace(' ', '_')
        if (cleanFact.length !in 5..180 || cleanEvidence.length !in 3..180 ||
            !safeKey.matches(key) || confidence < 0.78 ||
            prohibited.containsMatchIn(cleanFact) || prohibited.containsMatchIn(cleanEvidence) ||
            malformedFact.containsMatchIn(cleanFact) ||
            uncertain.containsMatchIn(cleanEvidence) || temporary.containsMatchIn(cleanEvidence) ||
            cleanEvidence.trimEnd().endsWith('?')
        ) return null

        val preference = parsePreferenceFact(cleanFact)
        if (preference != null) {
            if (!positivePreference.containsMatchIn(cleanEvidence) && !negativePreference.containsMatchIn(cleanEvidence)) {
                return null
            }
            val evidenceNegative = negativePreference.containsMatchIn(cleanEvidence)
            if (preference.positive == evidenceNegative) return null

            if (preference.personName == null) {
                if (!selfPreference.containsMatchIn(cleanEvidence)) return null
            } else {
                val namedEvidence = mentionsExactName(cleanEvidence, preference.personName) &&
                    namedPersonPreferenceEvidence(cleanEvidence, preference.personName)
                val recentPerson = MemoryWorkingContext.recentPerson
                val contextualEvidence = !namedEvidence &&
                    contextualPersonPronoun.containsMatchIn(cleanEvidence) &&
                    positiveOrNegativePreferenceEvidence(cleanEvidence) &&
                    recentPerson != null && samePerson(recentPerson, preference.personName) &&
                    mentionsExactName(conversationContext, preference.personName)
                if (!namedEvidence && !contextualEvidence) return null
            }
        }

        val semanticContext = semanticNormalize(conversationContext)
        val semanticEvidence = semanticNormalize(cleanEvidence)
        val semanticFact = semanticNormalize(cleanFact)
        val contextTokens = meaningfulTokens(semanticContext)
        val evidenceTokens = meaningfulTokens(semanticEvidence)
        val factTokens = meaningfulTokens(semanticFact)
        if (evidenceTokens.isEmpty() || contextTokens.isEmpty()) return null
        val evidenceGrounding = evidenceTokens.count(contextTokens::contains).toDouble() / evidenceTokens.size
        val factGrounding = factTokens.count(contextTokens::contains).toDouble() / factTokens.size.coerceAtLeast(1)
        if (evidenceGrounding < 0.70 || factGrounding < 0.40) return null

        val sensitivity = when {
            sensitive.containsMatchIn(cleanFact) || sensitive.containsMatchIn(cleanEvidence) ->
                MemorySensitivity.SENSITIVE
            category in LOW_RISK_CATEGORIES -> MemorySensitivity.LOW
            else -> MemorySensitivity.PERSONAL
        }

        val base = if (preference != null) {
            val subject = canonicalPreferenceSubject(preference.subject) ?: return null
            val subjectKey = normalize(subject)
            if (preference.personName == null) {
                MemoryCandidate(
                    category = MemoryCategory.PREFERENCE,
                    fact = if (preference.positive) "Zopy likes $subject" else "Zopy does not like $subject",
                    stableKey = "preference:likes:$subjectKey",
                    sensitivity = MemorySensitivity.LOW,
                    confidence = confidence.coerceIn(0.0, 0.95),
                    source = "gemini_grounded_conversation",
                    provenance = MemoryProvenance.GEMINI_GROUNDED_PROPOSAL
                )
            } else {
                val name = canonicalPersonName(preference.personName) ?: return null
                MemoryCandidate(
                    category = MemoryCategory.PERSON,
                    fact = if (preference.positive) "$name likes $subject" else "$name does not like $subject",
                    stableKey = "person:${personToken(name)}:preference:$subjectKey",
                    sensitivity = MemorySensitivity.PERSONAL,
                    confidence = confidence.coerceIn(0.0, 0.95),
                    source = "gemini_grounded_conversation",
                    provenance = MemoryProvenance.GEMINI_GROUNDED_PROPOSAL,
                    entityId = NaturalMemoryExtractor.stablePersonId(name),
                    entityName = name
                )
            }
        } else {
            MemoryCandidate(
                category = category,
                fact = cleanFact,
                stableKey = "semantic:${category.name.lowercase(Locale.ROOT)}:$key",
                sensitivity = sensitivity,
                confidence = confidence.coerceIn(0.0, 0.95),
                source = "gemini_grounded_conversation",
                provenance = MemoryProvenance.GEMINI_GROUNDED_PROPOSAL
            )
        }

        return PreferenceMemoryIdentity.canonicalize(MemoryRelationshipPolicy.canonicalize(base))
    }

    private fun parsePreferenceFact(fact: String): PreferenceFact? {
        Regex(
            "^(?:Zopy|The user) (?:likes|loves|enjoys|prefers) (.+)$",
            RegexOption.IGNORE_CASE
        ).matchEntire(fact)?.let { return PreferenceFact(null, it.groupValues[1], true) }
        Regex(
            "^(?:Zopy|The user) (?:does not|doesn't|doesnt) (?:like|love|enjoy|prefer) (.+)$",
            RegexOption.IGNORE_CASE
        ).matchEntire(fact)?.let { return PreferenceFact(null, it.groupValues[1], false) }

        val person = "([\\p{L}][\\p{L}'-]{1,29}(?:\\s+[\\p{L}][\\p{L}'-]{1,29}){0,2})"
        Regex("^$person (?:likes|loves|enjoys|prefers) (.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(fact)?.let { return PreferenceFact(it.groupValues[1], it.groupValues[2], true) }
        Regex("^$person (?:does not|doesn't|doesnt) (?:like|love|enjoy|prefer) (.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(fact)?.let { return PreferenceFact(it.groupValues[1], it.groupValues[2], false) }
        return null
    }

    private fun namedPersonPreferenceEvidence(evidence: String, name: String): Boolean {
        val escaped = Regex.escape(name)
        return listOf(
            Regex("(?:^|\\s)$escaped\\s+ko\\b.{1,90}(?:pasand|accha|achha|acha|aacha|achcha|acchi|achhi|achi)", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\s)$escaped\\b.{1,90}(?:pasand\\s+karta|pasand\\s+karti|enjoy\\s+karta|enjoy\\s+karti)", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\s)$escaped\\s+(?:likes|loves|enjoys|prefers|does\\s+not\\s+like|doesn't\\s+like)", RegexOption.IGNORE_CASE)
        ).any { it.containsMatchIn(evidence) }
    }

    private fun positiveOrNegativePreferenceEvidence(value: String): Boolean =
        positivePreference.containsMatchIn(value) || negativePreference.containsMatchIn(value)

    private fun samePerson(left: String, right: String): Boolean =
        left.equals(right, ignoreCase = true) || BestFriendNameSimilarity.likelySame(left, right)

    private fun mentionsExactName(value: String, name: String): Boolean =
        Regex("(?:^|[^\\p{L}\\p{N}])${Regex.escape(name)}(?:$|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)

    private fun canonicalPreferenceSubject(raw: String): String? {
        val clean = raw.trim().trim('"', '\'', '.', ',', '!', '?').replace(Regex("\\s+"), " ")
        if (clean.length !in 2..80 || clean.split(' ').size !in 1..12) return null
        val normalized = normalize(clean)
        return when {
            Regex("^(?:code|codes|coding)(?:\\s+(?:karna|karne|karni))?$").matches(normalized) -> "coding"
            else -> clean.replace(Regex("\\s+(?:karna|karne|karni)$", RegexOption.IGNORE_CASE), "").trim()
        }.takeIf { it.length >= 2 }
    }

    private fun canonicalPersonName(raw: String): String? {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 2..60 || clean.split(' ').size !in 1..3) return null
        val lower = clean.lowercase(Locale.ROOT)
        if (lower in setOf("zopy", "the user", "user", "i", "me", "main", "mai", "mujhe", "you", "tum", "vo", "woh")) return null
        return clean.split(' ').joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
        }
    }

    private fun semanticNormalize(value: String): String {
        var normalized = value.lowercase(Locale.ROOT)
            .replace(Regex("\\b(?:accha|achha|acha|aacha|achcha|acchi|achhi|achi)\\s+nahi\\s+lag(?:ta|ti|ata|ati)\\b"), " not like ")
            .replace(Regex("\\bpasand[ae]?\\s+nahi\\b"), " not like ")
            .replace(Regex("\\b(?:accha|achha|acha|aacha|achcha|acchi|achhi|achi)\\s+lag(?:ta|ti|ata|ati)\\b"), " like ")
            .replace(Regex("\\bpasand[ae]?\\b"), " like ")
            .replace(Regex("\\b(?:maza|mazza)\\s+(?:aata|ata)\\b"), " enjoy ")
            .replace(Regex("\\b(?:likes|liked|loves|prefers|enjoys)\\b")) { match ->
                when (match.value) {
                    "likes", "liked" -> "like"
                    "loves" -> "love"
                    "prefers" -> "prefer"
                    else -> "enjoy"
                }
            }
            .replace(Regex("\\b(?:code|codes)\\s+(?:karna|karne|karni)\\b"), " coding ")
            .replace(Regex("\\bcodes?\\b"), " coding ")
        normalized = normalized.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return normalized
    }

    private fun meaningfulTokens(value: String): Set<String> = normalize(value)
        .split(' ')
        .filter { it.length >= 2 && it !in stopWords }
        .map {
            when {
                it in setOf("likes", "liked") -> "like"
                it == "loves" -> "love"
                it == "prefers" -> "prefer"
                it == "enjoys" -> "enjoy"
                it.endsWith('s') && it.length > 4 -> it.dropLast(1)
                else -> it
            }
        }
        .toSet()

    private fun personToken(value: String): String = normalize(value).replace(' ', '_').take(36)

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}:_-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val LOW_RISK_CATEGORIES = setOf(
        MemoryCategory.PREFERENCE,
        MemoryCategory.COMMUNICATION_STYLE,
        MemoryCategory.WORKFLOW,
        MemoryCategory.APP_USAGE,
        MemoryCategory.SOLUTION
    )
}
