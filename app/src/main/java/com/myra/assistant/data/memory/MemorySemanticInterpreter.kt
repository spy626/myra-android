package com.myra.assistant.data.memory

import java.util.Locale

enum class MemorySemanticIntent {
    ADD_RELATIONSHIP,
    REMOVE_RELATIONSHIP,
    REPLACE_RELATIONSHIP,
    ADD_LINKED_FACT,
    RENAME_ENTITY,
    DELETE_ENTITY,
    RECALL,
    TRANSIENT_CONTEXT,
    CLARIFY,
    NONE
}

enum class PersonRelationship(val key: String) {
    FRIEND("friend"), GOOD_FRIEND("good_friend"), BEST_FRIEND("best_friend")
}

enum class MemoryTemporalScope { CURRENT, HISTORICAL, TEMPORARY, RECURRING, UNSPECIFIED }

/**
 * A bounded semantic frame. It identifies the property being discussed before deciding
 * whether a correction changes a relationship, an entity name, or no durable memory.
 * It is evidence for MemoryBrainCoordinator; it never writes Room.
 */
data class MemorySemanticFrame(
    val intent: MemorySemanticIntent,
    val person: String? = null,
    val replacementPerson: String? = null,
    val relationship: PersonRelationship? = null,
    val replacementRelationship: PersonRelationship? = null,
    val temporalScope: MemoryTemporalScope = MemoryTemporalScope.UNSPECIFIED,
    val fact: String? = null,
    val confidence: Double = 0.0,
    val evidence: String = "local_semantic_frame"
)

/**
 * Deterministic grammar for high-confidence person semantics. Patterns describe predicates
 * and grammatical roles; names are open Unicode captures and never appear in production data.
 * Ambiguous meaning is deliberately returned as CLARIFY/NONE for Gemini or the user to resolve.
 */
object MemorySemanticInterpreter {
    private val questionWords = Regex(
        "(?:^|\\s)(?:kya|kaun|kaun\\s+kaun|kaunsa|kaunsi|kiska|kis|kab|who|what|which|when)(?:\\s|$)|^(?:do|does|is|are)\\s+",
        RegexOption.IGNORE_CASE
    )
    private val memorySubject = Regex(
        "\\b(?:dost|friend|relationship|naam|name|yaad|memory|remember|person|saath|with)\\b",
        RegexOption.IGNORE_CASE
    )
    private val name = "([\\p{L}][\\p{L}'-]{1,39})"
    private val friendWord = "(?:dost|friend)"
    private val now = "(?:ab|abhi|now|anymore)"

    private val wholePersonDelete = Regex(
        "^$name\\s+(?:ko\\s+)?(?:meri\\s+)?(?:memory|yaad)\\s+(?:se\\s+)?(?:hata|remove|delete|forget)(?:\\s+do)?$",
        RegexOption.IGNORE_CASE
    )
    private val explicitNameCorrection = listOf(
        Regex("^$name\\s+ka\\s+naam\\s+(?:actually\\s+)?$name\\s+(?:hai|he)$", RegexOption.IGNORE_CASE),
        Regex("^$name(?:'s)?\\s+name\\s+is\\s+(?:actually\\s+)?$name$", RegexOption.IGNORE_CASE),
        Regex("^$name\\s+is\\s+actually\\s+(?:named\\s+)?$name$", RegexOption.IGNORE_CASE)
    )
    private val relationshipReplacement = Regex(
        "^(?:ab\\s+)?(?:mera|meri|my)\\s+$friendWord\\s+$name\\s+(?:nahi|not)(?:\\s+hai|\\s+he)?[, ]+$name\\s+(?:hai|he|is)$",
        RegexOption.IGNORE_CASE
    )
    private val relationshipEnded = listOf(
        Regex("^(?:ab\\s+)?$name\\s+(?:ab\\s+)?(?:mera|meri|my)\\s+$friendWord\\s+(?:nahi\\s+(?:hai|he|raha)|nahi|not\\s+anymore)$", RegexOption.IGNORE_CASE),
        Regex("^$name\\s+(?:ab\\s+)?(?:mera|meri|my)\\s+$friendWord\\s+nahi\\s+raha$", RegexOption.IGNORE_CASE),
        Regex("^$name\\s+is\\s+not\\s+(?:my\\s+)?friend(?:\\s+anymore)?$", RegexOption.IGNORE_CASE),
        Regex("^(?:we|hum)\\s+(?:are|ab)\\s+not\\s+friends\\s+anymore$", RegexOption.IGNORE_CASE)
    )
    private val downgrade = Regex(
        "^$name\\s+$now?\\s*(?:mera|meri|my)?\\s*(?:best\\s+friend)\\s+(?:nahi|not)(?:\\s+hai)?[, ]+(?:bas\\s+)?(?:normal\\s+)?$friendWord\\s+(?:hai|he|now)?$",
        RegexOption.IGNORE_CASE
    )
    private val currentFriend = listOf(
        Regex("^(?:ab\\s+)?$name\\s+(?:bhi\\s+)?(?:mera|meri|my)\\s+(?:(bohot|bahut|very)\\s+)?(?:(accha|achha|good|close)\\s+)?$friendWord\\s+(?:hai|he|is)$", RegexOption.IGNORE_CASE),
        Regex("^(?:mera|meri|my)\\s+$friendWord\\s+$name\\s+(?:hai|he|is)$", RegexOption.IGNORE_CASE),
        Regex("^$name\\s+is\\s+(?:a\\s+)?(?:my\\s+)?(close\\s+|good\\s+)?friend$", RegexOption.IGNORE_CASE),
        Regex("^i\\s+consider\\s+$name\\s+(?:a|my)\\s+(close\\s+|good\\s+)?friend$", RegexOption.IGNORE_CASE)
    )
    private val historicalFriend = Regex(
        "^(?:pehle\\s+)?$name\\s+(?:mera|meri|my)\\s+$friendWord\\s+(?:tha|thi|used\\s+to\\s+be)$",
        RegexOption.IGNORE_CASE
    )
    private val childhoodFriend = Regex(
        "^$name\\s+(?:mera|meri|my)\\s+(?:bachpan\\s+ka|childhood)\\s+$friendWord\\s+(?:hai|he|is)$",
        RegexOption.IGNORE_CASE
    )
    private val temporaryTogether = Regex(
        "^(?:aaj|today)\\s+(?:(?:main|i)\\s+)?$name\\s+(?:ke\\s+saath|with)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val contextualFriend = Regex(
        "^(?:woh|vo|he|she)\\s+(?:mera|meri|my)\\s+(?:(bohot|bahut|very)\\s+)?(?:(accha|achha|good|close)\\s+)?$friendWord\\s+(?:hai|he|is)$",
        RegexOption.IGNORE_CASE
    )
    private val contextualCreator = Regex(
        "^(?:woh|vo|he|she)\\s+(?:gaming|game)\\s+(?:videos?|content)\\s+(?:banata|banati|banate|creates?|makes?)(?:\\s+hai)?$",
        RegexOption.IGNORE_CASE
    )
    private val nonRelationshipNegation = Regex(
        "\\b(?:baat\\s+nahi|call\\s+nahi|didn'?t\\s+call|didn'?t\\s+talk|not\\s+talk)\\b",
        RegexOption.IGNORE_CASE
    )

    fun interpret(raw: String, active: List<MemoryEntity>, recentPerson: String?): MemorySemanticFrame {
        val text = clean(raw)
        if (text.isBlank()) return MemorySemanticFrame(MemorySemanticIntent.NONE)
        if (isQuestion(text)) return MemorySemanticFrame(MemorySemanticIntent.RECALL, fact = text, confidence = .99)
        val clauses = text.split(Regex("[,;]+")).map(::clean).filter(String::isNotBlank)
        if (clauses.size > 1) {
            val temporaryPerson = clauses.firstNotNullOfOrNull { clause ->
                temporaryTogether.matchEntire(clause)?.groupValues?.get(1)?.let(::canonical)
            }
            val contextualRelationship = clauses.firstNotNullOfOrNull(contextualFriend::matchEntire)
            if (temporaryPerson != null && contextualRelationship != null) {
                val good = contextualRelationship.groupValues.drop(1).any { it.isNotBlank() }
                return MemorySemanticFrame(
                    MemorySemanticIntent.ADD_RELATIONSHIP,
                    person = temporaryPerson,
                    relationship = if (good) PersonRelationship.GOOD_FRIEND else PersonRelationship.FRIEND,
                    temporalScope = MemoryTemporalScope.CURRENT,
                    confidence = .96
                )
            }
        }
        wholePersonDelete.matchEntire(text)?.let {
            return MemorySemanticFrame(MemorySemanticIntent.DELETE_ENTITY, canonical(it.groupValues[1]), confidence = .99)
        }
        explicitNameCorrection.firstNotNullOfOrNull { it.matchEntire(text) }?.let {
            return MemorySemanticFrame(
                MemorySemanticIntent.RENAME_ENTITY,
                canonical(it.groupValues[1]), canonical(it.groupValues[2]), confidence = .99
            )
        }
        relationshipReplacement.matchEntire(text)?.let {
            return MemorySemanticFrame(
                MemorySemanticIntent.REPLACE_RELATIONSHIP,
                canonical(it.groupValues[1]), canonical(it.groupValues[2]), PersonRelationship.FRIEND,
                temporalScope = MemoryTemporalScope.CURRENT, confidence = .98
            )
        }
        downgrade.matchEntire(text)?.let {
            return MemorySemanticFrame(
                MemorySemanticIntent.REPLACE_RELATIONSHIP,
                canonical(it.groupValues[1]), relationship = PersonRelationship.BEST_FRIEND,
                replacementRelationship = PersonRelationship.FRIEND,
                temporalScope = MemoryTemporalScope.CURRENT, confidence = .98
            )
        }
        if (!nonRelationshipNegation.containsMatchIn(text)) {
            relationshipEnded.firstNotNullOfOrNull { it.matchEntire(text) }?.let {
                val resolved = it.groupValues.getOrNull(1)?.takeIf(String::isNotBlank)?.let(::canonical)
                    ?: uniqueKnownPerson(active, recentPerson)
                    ?: return MemorySemanticFrame(MemorySemanticIntent.CLARIFY, confidence = .9)
                return MemorySemanticFrame(
                    MemorySemanticIntent.REMOVE_RELATIONSHIP, resolved,
                    relationship = PersonRelationship.FRIEND,
                    temporalScope = MemoryTemporalScope.CURRENT, confidence = .98
                )
            }
        }
        historicalFriend.matchEntire(text)?.let {
            val person = canonical(it.groupValues[1])
            return MemorySemanticFrame(
                MemorySemanticIntent.ADD_LINKED_FACT, person = person,
                temporalScope = MemoryTemporalScope.HISTORICAL,
                fact = "$person was Zopy's friend in the past", confidence = .96
            )
        }
        childhoodFriend.matchEntire(text)?.let {
            val person = canonical(it.groupValues[1])
            return MemorySemanticFrame(
                MemorySemanticIntent.ADD_RELATIONSHIP, person = person,
                relationship = PersonRelationship.FRIEND,
                temporalScope = MemoryTemporalScope.CURRENT,
                fact = "$person is Zopy's childhood friend", confidence = .97
            )
        }
        currentFriend.firstNotNullOfOrNull { it.matchEntire(text) }?.let { match ->
            val person = canonical(match.groupValues[1])
            val good = match.groupValues.drop(2).any { it.isNotBlank() }
            return MemorySemanticFrame(
                MemorySemanticIntent.ADD_RELATIONSHIP, person = person,
                relationship = if (good) PersonRelationship.GOOD_FRIEND else PersonRelationship.FRIEND,
                temporalScope = MemoryTemporalScope.CURRENT, confidence = .97
            )
        }
        contextualFriend.matchEntire(text)?.let { match ->
            val person = uniqueKnownPerson(active, recentPerson)
                ?: return MemorySemanticFrame(MemorySemanticIntent.CLARIFY, confidence = .9)
            val good = match.groupValues.drop(1).any { it.isNotBlank() }
            return MemorySemanticFrame(
                MemorySemanticIntent.ADD_RELATIONSHIP, person = person,
                relationship = if (good) PersonRelationship.GOOD_FRIEND else PersonRelationship.FRIEND,
                temporalScope = MemoryTemporalScope.CURRENT, confidence = .94
            )
        }
        contextualCreator.matchEntire(text)?.let {
            val person = uniqueKnownPerson(active, recentPerson)
                ?: return MemorySemanticFrame(MemorySemanticIntent.CLARIFY, confidence = .9)
            return MemorySemanticFrame(
                MemorySemanticIntent.ADD_LINKED_FACT, person = person,
                fact = "$person creates gaming videos", confidence = .94
            )
        }
        temporaryTogether.matchEntire(text)?.let {
            return MemorySemanticFrame(
                MemorySemanticIntent.TRANSIENT_CONTEXT, person = canonical(it.groupValues[1]),
                temporalScope = MemoryTemporalScope.TEMPORARY, fact = it.groupValues[2], confidence = .94
            )
        }
        return MemorySemanticFrame(MemorySemanticIntent.NONE)
    }

    private fun isQuestion(text: String): Boolean =
        memorySubject.containsMatchIn(text) && (text.endsWith('?') || questionWords.containsMatchIn(text))

    private fun uniqueKnownPerson(active: List<MemoryEntity>, recentPerson: String?): String? {
        recentPerson?.takeIf(String::isNotBlank)?.let { return canonical(it) }
        return active.mapNotNull { it.entityName }.distinctBy(::token).singleOrNull()?.let(::canonical)
    }

    private fun clean(value: String) = value.trim().trimEnd('.', ',', '!', '।')
        .replace(Regex("\\s+"), " ")
    private fun canonical(value: String) = value.trim().split(Regex("\\s+")).joinToString(" ") {
        it.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase)
    }
    fun token(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40)
}
