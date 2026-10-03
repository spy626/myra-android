package com.myra.assistant.voice

import com.myra.assistant.data.memory.AuthoritativeMemoryTurnEvidence
import java.text.Normalizer

/** One immutable finalized transcript shared by UI and every durable-memory consumer. */
data class FinalSemanticUserUtterance(
    val sessionId: String,
    val turnId: Long,
    val utteranceId: String,
    val rawGeminiTranscript: String,
    val canonicalSemanticText: String,
    val displayText: String,
    val canonicalNameTokens: List<String>,
    val displayNameTokens: List<String>
) {
    // Display text is presentation only. Durable authorization receives every finalized form.
    val memoryExtractorInput: String get() = displayText
    val semanticConsistency: Boolean get() = canonicalSemanticText.isNotBlank()

    val memoryEvidence: AuthoritativeMemoryTurnEvidence get() = AuthoritativeMemoryTurnEvidence(
        turnId = turnId,
        canonicalText = canonicalSemanticText,
        displayText = displayText,
        protectedCanonicalNames = canonicalNameTokens,
        protectedDisplayNames = displayNameTokens,
        sessionId = sessionId,
        utteranceId = utteranceId,
        contextGeneration = turnId
    )

    companion object {
        fun from(
            sessionId: String,
            turnId: Long,
            rawGeminiTranscript: String,
            formatted: FinalTranscriptDisplayFormatter.Result
        ): FinalSemanticUserUtterance {
            val display = formatted.display
            val semantic = Normalizer.normalize(rawGeminiTranscript, Normalizer.Form.NFC)
                .replace(Regex("\\s+"), " ").trim()
            val displayNames = formatted.protectedNameTokens.filter { name ->
                Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(display)
            }
            return FinalSemanticUserUtterance(
                sessionId = sessionId,
                turnId = turnId,
                utteranceId = "$sessionId:$turnId",
                rawGeminiTranscript = rawGeminiTranscript,
                canonicalSemanticText = semantic,
                displayText = display,
                canonicalNameTokens = formatted.protectedNameTokens,
                displayNameTokens = displayNames
            )
        }
    }
}
