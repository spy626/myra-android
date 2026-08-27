package com.myra.assistant.data.memory

import java.util.Locale
import kotlin.math.abs

data class BestFriendNameCorrection(val oldName: String, val newName: String)

data class BestFriendNameCorrectionDecision(
    val correctionIntentDetected: Boolean,
    val correctionIntentPattern: String? = null,
    val oldNameCandidate: String? = null,
    val newNameCandidate: String? = null,
    val newNameValidation: String = "not_evaluated",
    val rejectionReason: String? = null,
    val correction: BestFriendNameCorrection? = null
) {
    val databaseMutationAllowed: Boolean get() = correction != null
}

object BestFriendNameCorrectionParser {
    private data class IntentMatch(val pattern: String, val oldName: String?, val newName: String)

    private val relationshipPair = Regex(
        "^([\\p{L}][\\p{L} .'-]{0,39})\\s+(?:nahi|nahin|nehi|nai|not)[, ]+" +
            "([\\p{L}][\\p{L} .'-]{0,39})\\s+(?:mera|meri|mere)\\s+best\\s+(?:friend|frend)\\s+(?:hai|he)$",
        RegexOption.IGNORE_CASE
    )
    private val saidPair = Regex(
        "^(?:maine|mainne)\\s+([\\p{L}][\\p{L} .'-]{0,39})\\s+(?:nahi|nahin|nehi|nai)\\s+" +
            "(?:kaha|bola)[, ]+([\\p{L}][\\p{L} .'-]{0,39})\\s+(?:kaha|bola)$",
        RegexOption.IGNORE_CASE
    )
    private val nameIsPair = Regex(
        "^([\\p{L}][\\p{L} .'-]{0,39})\\s+ka\\s+naam\\s+([\\p{L}][\\p{L} .'-]{0,39})\\s+(?:hai|he)$",
        RegexOption.IGNORE_CASE
    )
    private val directPair = Regex(
        "^([\\p{L}][\\p{L} .'-]{0,39})\\s+(?:nahi|nahin|nehi|nai|not)[, ]+([\\p{L}][\\p{L} .'-]{0,39})$",
        RegexOption.IGNORE_CASE
    )
    private val explicitSingle = Regex(
        "^(?:(?:no|nahi|nahin|actually|sorry)[, ]+|(?:i said|maine kaha|naam)\\s+)([\\p{L}][\\p{L} .'-]{0,39})$",
        RegexOption.IGNORE_CASE
    )
    private val rejected = setOf("haan", "han", "yes", "nahi", "no", "okay", "ok", "thanks", "thank you")
    private val hindiParticles = setOf("ne", "ko", "se", "ka", "ki", "ke", "mein", "me", "par")
    private val validNameShape = Regex("[\\p{L}][\\p{L}'-]*(?: [\\p{L}][\\p{L}'-]*){0,2}")

    fun parse(raw: String, lastSavedName: String?): BestFriendNameCorrection? =
        analyze(raw, lastSavedName).correction

    fun analyze(raw: String, lastSavedName: String?): BestFriendNameCorrectionDecision {
        val clean = clean(raw)
        val intent = detectIntent(clean) ?: return BestFriendNameCorrectionDecision(
            correctionIntentDetected = false,
            rejectionReason = "no_explicit_correction_intent"
        )

        val spokenOld = intent.oldName?.let(BestFriendNameCanonicalizer::canonicalize)
        val oldName = if (spokenOld != null) {
            lastSavedName?.takeIf { looksLikeSameSpokenName(it, spokenOld) } ?: spokenOld
        } else {
            lastSavedName?.takeIf { it.isNotBlank() }
        }
        if (oldName == null) return rejected(intent, spokenOld, "missing_old_entity")

        val validation = validateNewName(intent.newName)
        if (validation != null) return rejected(intent, oldName, validation)

        val newName = BestFriendNameCanonicalizer.canonicalize(intent.newName.trim())
        val canonicalOld = BestFriendNameCanonicalizer.canonicalize(oldName)
        if (newName.equals(canonicalOld, ignoreCase = true)) {
            return rejected(intent, canonicalOld, "old_and_new_names_are_identical", newName)
        }
        return BestFriendNameCorrectionDecision(
            correctionIntentDetected = true,
            correctionIntentPattern = intent.pattern,
            oldNameCandidate = canonicalOld,
            newNameCandidate = newName,
            newNameValidation = "valid",
            correction = BestFriendNameCorrection(canonicalOld, newName)
        )
    }

    /** Returns null only for a conservatively valid complete person name. */
    fun validateNewName(candidate: String): String? {
        val clean = candidate.trim().replace(Regex("\\s+"), " ")
        if (!validNameShape.matches(clean)) return "invalid_name_shape"
        val words = clean.lowercase(Locale.ROOT).split(' ')
        if (words.any { it in hindiParticles }) return "contains_hindi_particle"
        if (words.joinToString(" ") in rejected) return "not_a_person_name"
        return null
    }

    fun needsClearCorrectedName(raw: String): Boolean {
        val intent = detectIntent(clean(raw)) ?: return false
        val old = intent.oldName ?: return false
        return BestFriendNameCanonicalizer.canonicalize(old)
            .equals(BestFriendNameCanonicalizer.canonicalize(intent.newName), ignoreCase = true)
    }

    /** Keeps the database target when ASR heard both sides as the same name. */
    fun ambiguousOldName(raw: String, lastSavedName: String?): String? {
        val intent = detectIntent(clean(raw)) ?: return null
        val spokenOld = intent.oldName?.let(BestFriendNameCanonicalizer::canonicalize) ?: return null
        return lastSavedName?.takeIf { looksLikeSameSpokenName(it, spokenOld) } ?: spokenOld
    }

    private fun detectIntent(clean: String): IntentMatch? {
        relationshipPair.matchEntire(clean)?.let { return IntentMatch("not_pair_with_relationship", it.groupValues[1], it.groupValues[2]) }
        saidPair.matchEntire(clean)?.let { return IntentMatch("said_not_pair", it.groupValues[1], it.groupValues[2]) }
        nameIsPair.matchEntire(clean)?.let { return IntentMatch("name_is_pair", it.groupValues[1], it.groupValues[2]) }
        directPair.matchEntire(clean)?.let { return IntentMatch("direct_not_pair", it.groupValues[1], it.groupValues[2]) }
        explicitSingle.matchEntire(clean)?.let { return IntentMatch("explicit_single_with_recent_target", null, it.groupValues[1]) }
        return null
    }

    private fun rejected(intent: IntentMatch, oldName: String?, reason: String, newName: String = intent.newName.trim()) =
        BestFriendNameCorrectionDecision(
            correctionIntentDetected = true,
            correctionIntentPattern = intent.pattern,
            oldNameCandidate = oldName,
            newNameCandidate = newName,
            newNameValidation = if (reason == "missing_old_entity") "not_evaluated" else "rejected",
            rejectionReason = reason
        )

    private fun clean(raw: String): String = raw.trim()
        .trimEnd('.', ',', '?', '!', '।')
        .replace(Regex("\\s+"), " ")

    private fun looksLikeSameSpokenName(left: String, right: String): Boolean {
        val a = soundKey(left)
        val b = soundKey(right)
        if (a.firstOrNull() != b.firstOrNull() || abs(a.length - b.length) > 2) return false
        return editDistance(a, b) <= 2
    }

    private fun soundKey(value: String): String = value.lowercase(Locale.ROOT)
        .replace("ph", "f").replace('v', 'f').replace('p', 'f')
        .replace(Regex("[^a-z]"), "")
        .filterNot { it in "aeiou" }

    private fun editDistance(left: String, right: String): Int {
        val row = IntArray(right.length + 1) { it }
        left.forEachIndexed { i, a ->
            var diagonal = row[0]
            row[0] = i + 1
            right.forEachIndexed { j, b ->
                val above = row[j + 1]
                row[j + 1] = minOf(row[j + 1] + 1, row[j] + 1, diagonal + if (a == b) 0 else 1)
                diagonal = above
            }
        }
        return row.last()
    }
}
