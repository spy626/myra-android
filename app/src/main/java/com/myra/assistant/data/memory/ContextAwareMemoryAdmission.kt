package com.myra.assistant.data.memory

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Small in-process conversation slice used only to decide whether a turn is durable memory. */
internal data class MemoryContextTurn(
    val sender: String,
    val text: String,
    val turnId: Long = 0L
)

internal data class MemoryAdmissionResult(
    val candidates: List<JarvisMemoryCandidate>,
    val reason: String
)

/**
 * Context-aware memory admission gate.
 *
 * Chat history may keep every finalized turn, but long-term memory only receives a turn when its
 * meaning is durable and sufficiently clear. The fast path is entirely local: clear statements are
 * admitted immediately, while short contextual answers can be resolved from the recent dialogue.
 * Ambiguous, hypothetical, quoted/reported, command-like, or chitchat turns stay conversation-only.
 */
internal object ContextAwareMemoryAdmission {
    private val explicitRemember = Regex(
        "(?i)^(?:please\\s+)?(?:remember(?:\\s+that)?|yaad\\s+rakh(?:na)?|याद\\s+रख(?:ना)?)\\b"
    )
    private val hypothetical = Regex(
        "(?i)\\b(?:agar|if|suppose|imagine|kaash|shayad|maybe|perhaps|probably|possibly|might|would|could|" +
            "i\\s+think|mujhe\\s+lagta|lagta\\s+hai|ho\\s+sakta|ho\\s+sakti|हो\\s+सकता|काश)\\b"
    )
    private val reportedSpeech = Regex(
        "(?i)\\b(?:ne\\s+bola|ne\\s+kaha|said(?:\\s+that)?|told\\s+me|according\\s+to|" +
            "kehta\\s+hai|kehti\\s+hai|bolta\\s+hai|bolti\\s+hai)\\b"
    )
    private val commandLike = Regex(
        "(?i)^(?:open|close|play|pause|stop|call|send|message|click|tap|press|scroll|search|" +
            "kholo|khol|band\\s+karo|chalao|bhejo|dabao|dabaye|scroll\\s+karo|" +
            "youtube|whatsapp|chrome|settings)\\b"
    )
    private val unresolvedPronoun = Regex(
        "(?i)\\b(?:uske|uska|uski|unke|unka|unki|woh|wo|waha|wahan|him|her|there)\\b"
    )
    private val negativeRelationship = Regex(
        "(?i)\\b(?:best\\s+(?:friend|frend|dost)|friend|frend|dost)\\b.{0,30}\\b(?:nahi|nahin|not)\\b|" +
            "\\b(?:nahi|nahin|not)\\b.{0,30}\\b(?:best\\s+(?:friend|frend|dost)|friend|frend|dost)\\b"
    )
    private val vagueAnswers = setOf(
        "haan", "han", "ha", "yes", "yeah", "yep", "no", "nahi", "nahin", "nope",
        "ok", "okay", "acha", "accha", "achha", "hmm", "hm", "maybe", "shayad",
        "fine", "good", "thanks", "thank you", "pata nahi", "i dont know", "i don't know"
    )
    private val personalSignals = setOf(
        "i", "me", "my", "mine", "main", "maine", "mera", "meri", "mere", "mujhe",
        "hum", "ham", "hamara", "hamari", "मेरी", "मेरा", "मैं", "मुझे", "हम"
    )
    private val relationWords = setOf("friend", "frend", "dost", "best", "good", "accha", "acha", "achha")
    private val preferenceWords = setOf("prefer", "preference", "pasand", "like", "answers", "answer", "reply", "jawab")
    private val placeQuestionWords = setOf("where", "kaha", "kahan", "kahaan", "कहाँ")

    fun admit(
        rawText: String,
        extracted: List<JarvisMemoryCandidate>,
        recent: List<MemoryContextTurn>,
        knownPeople: List<String>
    ): MemoryAdmissionResult {
        val clean = compact(rawText)
        if (clean.length < 3) return MemoryAdmissionResult(emptyList(), "too_short")
        val explicit = explicitRemember.containsMatchIn(clean)
        if (!explicit && isConversationOnly(clean)) {
            return MemoryAdmissionResult(emptyList(), "conversation_only")
        }

        val contextual = contextualCandidates(clean, recent, knownPeople)
        val combined = (extracted + contextual).distinctBy { it.key }
        if (combined.isEmpty()) return MemoryAdmissionResult(emptyList(), "no_durable_meaning")

        val admitted = combined.mapNotNull { candidate ->
            when {
                candidate.type == JarvisMemoryType.RELATIONSHIP && rejectsPositiveRelationship(clean) -> null
                candidate.key.startsWith("episode:") -> resolveEpisode(candidate, clean, recent, knownPeople)
                else -> candidate
            }
        }.distinctBy { it.key }
        return MemoryAdmissionResult(
            admitted,
            if (admitted.isEmpty()) "ambiguous_context" else if (contextual.isNotEmpty()) "context_resolved" else "self_contained"
        )
    }


    fun rejectsPositiveRelationship(text: String): Boolean =
        negativeRelationship.containsMatchIn(compact(text))

    /**
     * Model proposals may enrich a meaningful turn, but cannot turn random conversation into memory.
     * Raw final user text remains the durable payload when a generic fallback is needed.
     */
    fun allowModelFallback(
        rawText: String,
        frame: MemorySemanticFrame,
        recent: List<MemoryContextTurn>,
        knownPeople: List<String>
    ): Boolean {
        val clean = compact(rawText)
        if (clean.length < 3) return false
        if (explicitRemember.containsMatchIn(clean)) return true
        if (isConversationOnly(clean)) return false

        val local = JarvisSimpleMemoryExtractor.extract(clean)
        if (admit(clean, local, recent, knownPeople).candidates.isNotEmpty()) return true
        if (frame.confidence < 0.72) return false
        if (looksLikeFragment(clean)) return false

        val normalized = normalize(clean)
        val tokens = normalized.split(' ').filter(String::isNotBlank)
        val personal = tokens.any(personalSignals::contains)
        val namedPersonGrounded = frame.person?.takeIf(String::isNotBlank)?.let { person ->
            containsWhole(normalized, normalize(person))
        } == true

        return when (frame.intent) {
            MemorySemanticIntent.ADD_GOAL -> personal || normalized.contains("goal") || normalized.contains("want to")
            MemorySemanticIntent.ADD_EPISODE -> personal || namedPersonGrounded || containsPastEventCue(normalized)
            MemorySemanticIntent.ADD_LINKED_FACT -> namedPersonGrounded && tokens.size >= 4
            MemorySemanticIntent.ADD_FACT,
            MemorySemanticIntent.UPDATE_FACT,
            MemorySemanticIntent.SUPERSEDE_FACT -> personal && tokens.size >= 4
            MemorySemanticIntent.ADD_RELATIONSHIP ->
                !rejectsPositiveRelationship(clean) && namedPersonGrounded && tokens.any(relationWords::contains)
            else -> false
        }
    }

    /** Resolve pronoun follow-ups locally so fast recall does not need a model round-trip. */
    fun resolveRecallQuery(
        query: String,
        recent: List<MemoryContextTurn>,
        knownPeople: List<String>
    ): String {
        val clean = compact(query)
        if (!unresolvedPronoun.containsMatchIn(clean)) return clean
        val person = mostRecentPerson(recent, knownPeople) ?: return clean
        return clean
            .replace(Regex("(?i)\\b(?:uske|unke)\\s+(?:saath|sath|saat)\\b"), "$person ke saath")
            .replace(Regex("(?i)\\b(?:with\\s+him|with\\s+her)\\b"), "with $person")
    }

    private fun contextualCandidates(
        clean: String,
        recent: List<MemoryContextTurn>,
        knownPeople: List<String>
    ): List<JarvisMemoryCandidate> {
        if (looksLikeFragment(clean) && isVague(clean)) return emptyList()
        // Only the immediately preceding assistant turn may supply omitted subject/meaning.
        // Looking farther back can make an unrelated later utterance inherit a stale question.
        val previousAssistant = recent.lastOrNull()
            ?.takeIf { it.sender.equals("assistant", ignoreCase = true) }
            ?.text?.let(::compact)
            ?: return resolvePronounOnly(clean, recent, knownPeople)
        val question = normalize(previousAssistant)
        val out = mutableListOf<JarvisMemoryCandidate>()

        if (isBestFriendQuestion(question) && looksLikeNameAnswer(clean)) {
            val name = cleanName(clean)
            out += JarvisMemoryCandidate(
                key = "relationship:${keyToken(name)}",
                type = JarvisMemoryType.RELATIONSHIP,
                value = "BEST_FRIEND|$name",
                fact = "$name is Zopy's best friend",
                subject = name,
                importance = 9
            )
        }

        if (isPreferenceQuestion(question) && !isVague(clean) && clean.split(' ').size <= 12) {
            val preference = JarvisMemoryCanonicalizer.canonicalPreference(clean)
            if (preference.length in 2..160) {
                out += JarvisMemoryCandidate(
                    key = "preference:${keyToken(preference)}",
                    type = JarvisMemoryType.PREFERENCE,
                    value = "LIKE|$preference",
                    fact = "Zopy likes $preference",
                    importance = 7
                )
            }
        }

        val companion = personFromCompanionQuestion(previousAssistant)
        if (companion != null && question.split(' ').any(placeQuestionWords::contains) && looksLikePlaceAnswer(clean)) {
            val place = clean.trim().trim('.', ',', '!', '?')
            val fact = "Zopy went to $place with $companion"
            out += JarvisMemoryCandidate(
                key = "episode:${shortHash(fact)}",
                type = JarvisMemoryType.NOTE,
                value = "FACT|$fact",
                fact = fact,
                subject = companion,
                importance = 6
            )
        }

        if (isHomeQuestion(question) && looksLikePlaceAnswer(clean)) {
            val place = clean.trim().trim('.', ',', '!', '?')
            out += JarvisMemoryCandidate(
                key = "personal:home",
                type = JarvisMemoryType.PERSONAL_FACT,
                value = place,
                fact = "Zopy lives in $place",
                importance = 8
            )
        }

        if (isProjectQuestion(question) && !isVague(clean) && clean.split(' ').size <= 20) {
            val project = clean.trim().trim('.', ',', '!', '?')
            out += JarvisMemoryCandidate(
                key = "project:${shortHash(project)}",
                type = JarvisMemoryType.PROJECT,
                value = project,
                fact = "Zopy is working on $project",
                importance = 8
            )
        }

        out += resolvePronounOnly(clean, recent, knownPeople)
        return out.distinctBy { it.key }
    }

    private fun resolvePronounOnly(
        clean: String,
        recent: List<MemoryContextTurn>,
        knownPeople: List<String>
    ): List<JarvisMemoryCandidate> {
        if (!unresolvedPronoun.containsMatchIn(clean) || !containsPastEventCue(normalize(clean))) return emptyList()
        val person = mostRecentPerson(recent, knownPeople) ?: return emptyList()
        val resolved = clean
            .replace(Regex("(?i)\\b(?:uske|unke)\\s+(?:saath|sath|saat)\\b"), "$person ke saath")
            .replace(Regex("(?i)\\b(?:with\\s+him|with\\s+her)\\b"), "with $person")
        if (resolved == clean || unresolvedPronoun.containsMatchIn(resolved)) return emptyList()
        val fact = compact(resolved)
        return listOf(
            JarvisMemoryCandidate(
                key = "episode:${shortHash(fact)}",
                type = JarvisMemoryType.NOTE,
                value = "FACT|$fact",
                fact = fact,
                subject = person,
                importance = 6
            )
        )
    }

    private fun resolveEpisode(
        candidate: JarvisMemoryCandidate,
        clean: String,
        recent: List<MemoryContextTurn>,
        knownPeople: List<String>
    ): JarvisMemoryCandidate? {
        if (!unresolvedPronoun.containsMatchIn(clean)) return candidate
        return resolvePronounOnly(clean, recent, knownPeople).firstOrNull()
    }

    private fun mostRecentPerson(recent: List<MemoryContextTurn>, knownPeople: List<String>): String? {
        if (knownPeople.isEmpty()) return null
        recent.asReversed().forEach { turn ->
            val normalized = normalize(turn.text)
            knownPeople.firstOrNull { person -> containsWhole(normalized, normalize(person)) }?.let { return it }
        }
        return knownPeople.singleOrNull()
    }

    private fun isConversationOnly(clean: String): Boolean {
        val normalized = normalize(clean)
        if (looksLikeQuestion(clean)) return true
        if (isVague(clean)) return true
        if (hypothetical.containsMatchIn(clean)) return true
        if (reportedSpeech.containsMatchIn(clean)) return true
        if (commandLike.containsMatchIn(clean)) return true
        return normalized.startsWith("can you ") || normalized.startsWith("could you ") ||
            normalized.startsWith("please open ") || normalized.startsWith("please send ")
    }


    private fun looksLikeQuestion(clean: String): Boolean {
        if (clean.trim().endsWith('?')) return true
        return Regex(
            "(?i)^(?:what|who|which|where|when|why|how|do i|did i|am i|is my|" +
                "kya|kaun|kab|kahan|kahaan|kyun|kaise|kiske|kis ke|क्या|कौन|कब|कहाँ|क्यों|कैसे)\\b"
        ).containsMatchIn(clean.trim())
    }

    private fun isVague(clean: String): Boolean = normalize(clean) in vagueAnswers

    private fun looksLikeFragment(clean: String): Boolean {
        val tokens = normalize(clean).split(' ').filter(String::isNotBlank)
        if (tokens.size <= 2) return true
        if (tokens.size <= 4 && unresolvedPronoun.containsMatchIn(clean)) return true
        return false
    }

    private fun isBestFriendQuestion(question: String): Boolean =
        (question.contains("best friend") || question.contains("best frend") || question.contains("best dost")) &&
            (question.contains("who") || question.contains("kaun") || question.contains("name") || question.contains("naam"))

    private fun isPreferenceQuestion(question: String): Boolean =
        question.split(' ').any(preferenceWords::contains) &&
            (question.contains("what") || question.contains("which") || question.contains("kya") ||
                question.contains("kaisa") || question.contains("kaise") || question.contains("type"))

    private fun isHomeQuestion(question: String): Boolean =
        (question.contains("where do you live") || question.contains("where you live") ||
            question.contains("kahan rehte") || question.contains("kaha rehte") || question.contains("kahaan rehte"))

    private fun isProjectQuestion(question: String): Boolean =
        question.contains("project") &&
            (question.contains("what") || question.contains("which") || question.contains("kya") || question.contains("kaun"))

    private fun personFromCompanionQuestion(text: String): String? {
        val patterns = listOf(
            Regex("(?i)\\b([\\p{L}][\\p{L}\\p{M}'-]{1,60})\\s+ke\\s+(?:saath|sath|saat)\\b"),
            Regex("(?i)\\bwith\\s+([\\p{L}][\\p{L}\\p{M}'-]{1,60})\\b")
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }?.let(::cleanName)
    }

    private fun looksLikeNameAnswer(clean: String): Boolean {
        if (isVague(clean)) return false
        val value = cleanName(clean)
        val tokens = value.split(' ').filter(String::isNotBlank)
        return tokens.size in 1..3 && value.none(Char::isDigit) &&
            value.all { it.isLetter() || it.isWhitespace() || it == '\'' || it == '-' }
    }

    private fun looksLikePlaceAnswer(clean: String): Boolean {
        if (isVague(clean) || clean.endsWith('?')) return false
        val tokens = normalize(clean).split(' ').filter(String::isNotBlank)
        if (tokens.isEmpty() || tokens.size > 12) return false
        if (tokens.any(personalSignals::contains)) return false
        return !commandLike.containsMatchIn(clean)
    }

    private fun containsPastEventCue(normalized: String): Boolean = listOf(
        " went ", " visited ", " travelled ", " traveled ", " met ", " bought ", " watched ", " ate ",
        " gaya ", " gya ", " gaye ", " gayi ", " ghumne ", " ghoomne ", " gumne ", " trip ", " travel "
    ).any { cue -> " $normalized ".contains(cue) }

    private fun containsWhole(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        return " $haystack ".contains(" $needle ")
    }

    private fun cleanName(value: String): String = value.trim().trim(' ', '.', ',', '!', '?', '।')
        .replace(Regex("\\s+"), " ").take(80)

    private fun compact(value: String): String = value.trim().replace(Regex("\\s+"), " ").take(500)

    private fun keyToken(value: String): String = normalize(value).replace(' ', '_').take(80)

    private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(normalize(value).toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
