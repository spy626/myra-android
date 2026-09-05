package com.myra.assistant.voice

import com.myra.assistant.data.memory.CorrectionTranscriptNormalizer
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
    // Memory's established name protection consumes the corrected display form. Intent and
    // action ownership consume canonicalSemanticText, which deliberately preserves Unicode.
    val memoryExtractorInput: String get() = displayText
    val correctionParserInput: String get() = displayText
    val deleteParserInput: String get() = displayText
    val clarificationResolverInput: String get() = displayText
    val semanticConsistency: Boolean get() = canonicalNameTokens == displayNameTokens

    companion object {
        fun from(
            sessionId: String,
            turnId: Long,
            rawGeminiTranscript: String,
            formatted: FinalTranscriptDisplayFormatter.Result
        ): FinalSemanticUserUtterance {
            // Entity protection has already happened inside formatted.display. Apply the
            // narrowly scoped correction normalizer only after that protection, never to
            // ICU's ambiguous karima/karīma output.
            val display = CorrectionTranscriptNormalizer.normalize(
                rawGeminiTranscript,
                formatted.display
            )
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
