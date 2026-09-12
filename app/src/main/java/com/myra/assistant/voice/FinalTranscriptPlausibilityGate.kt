package com.myra.assistant.voice

import java.lang.Character.UnicodeScript

enum class TranscriptScript { LATIN, DEVANAGARI, HANGUL, HAN, CYRILLIC, ARABIC, OTHER, NONE }
enum class TranscriptPlausibility { PLAUSIBLE, SUSPICIOUS }

data class TranscriptPlausibilityDecision(
    val detectedScripts: Set<TranscriptScript>,
    val dominantScript: TranscriptScript,
    val recentSessionLanguageProfile: String,
    val transcriptPlausibility: TranscriptPlausibility,
    val anomalyReason: String? = null
) {
    val semanticProcessingAllowed: Boolean get() = transcriptPlausibility == TranscriptPlausibility.PLAUSIBLE
    val userBubbleCommitAllowed: Boolean get() = semanticProcessingAllowed
    val memoryMutationAllowed: Boolean get() = semanticProcessingAllowed
}

/**
 * Conservative, session-aware guard for obviously implausible Gemini ASR scripts.
 * It never guesses a replacement transcript. Three consecutive finals dominated by the
 * same new script are treated as evidence of a genuine language switch, allowing future
 * multilingual use without trusting a single isolated anomaly.
 */
class FinalTranscriptPlausibilityGate {
    private var profile = "HINDI_HINGLISH"
    private var pendingForeignScript = TranscriptScript.NONE
    private var pendingForeignCount = 0

    fun preview(raw: String): TranscriptPlausibilityDecision = decide(raw, updateSession = false)

    fun assessFinal(raw: String): TranscriptPlausibilityDecision = decide(raw, updateSession = true)

    private fun decide(raw: String, updateSession: Boolean): TranscriptPlausibilityDecision {
        val counts = scriptCounts(raw)
        val detected = counts.filterValues { it > 0 }.keys
        val dominant = counts.maxByOrNull { it.value }?.key ?: TranscriptScript.NONE
        val acceptedForProfile = when (profile) {
            "HINDI_HINGLISH" -> dominant in setOf(
                TranscriptScript.LATIN,
                TranscriptScript.DEVANAGARI,
                TranscriptScript.NONE
            )
            else -> dominant.name == profile || dominant == TranscriptScript.LATIN || dominant == TranscriptScript.NONE
        }

        if (acceptedForProfile) {
            if (updateSession) {
                pendingForeignScript = TranscriptScript.NONE
                pendingForeignCount = 0
            }
            return TranscriptPlausibilityDecision(
                detectedScripts = detected,
                dominantScript = dominant,
                recentSessionLanguageProfile = profile,
                transcriptPlausibility = TranscriptPlausibility.PLAUSIBLE
            )
        }

        val nextCount = if (pendingForeignScript == dominant) pendingForeignCount + 1 else 1
        if (updateSession) {
            pendingForeignScript = dominant
            pendingForeignCount = nextCount
            if (nextCount >= LANGUAGE_SWITCH_EVIDENCE_TURNS) {
                profile = dominant.name
                pendingForeignScript = TranscriptScript.NONE
                pendingForeignCount = 0
                return TranscriptPlausibilityDecision(
                    detectedScripts = detected,
                    dominantScript = dominant,
                    recentSessionLanguageProfile = profile,
                    transcriptPlausibility = TranscriptPlausibility.PLAUSIBLE
                )
            }
        }
        return TranscriptPlausibilityDecision(
            detectedScripts = detected,
            dominantScript = dominant,
            recentSessionLanguageProfile = profile,
            transcriptPlausibility = TranscriptPlausibility.SUSPICIOUS,
            anomalyReason = "unexpected_dominant_script"
        )
    }

    private fun scriptCounts(raw: String): Map<TranscriptScript, Int> {
        val counts = mutableMapOf<TranscriptScript, Int>()
        var offset = 0
        while (offset < raw.length) {
            val codePoint = raw.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            val script = when (UnicodeScript.of(codePoint)) {
                UnicodeScript.LATIN -> TranscriptScript.LATIN
                UnicodeScript.DEVANAGARI -> TranscriptScript.DEVANAGARI
                UnicodeScript.HANGUL -> TranscriptScript.HANGUL
                UnicodeScript.HAN -> TranscriptScript.HAN
                UnicodeScript.CYRILLIC -> TranscriptScript.CYRILLIC
                UnicodeScript.ARABIC -> TranscriptScript.ARABIC
                else -> TranscriptScript.OTHER
            }
            counts[script] = counts.getOrDefault(script, 0) + 1
        }
        return counts
    }

    companion object {
        const val CLARIFICATION_REPLY = "Sorry, woh clear nahi suna. Ek baar phir bolo."
        private const val LANGUAGE_SWITCH_EVIDENCE_TURNS = 3
    }
}
