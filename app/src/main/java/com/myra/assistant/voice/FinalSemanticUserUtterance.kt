package com.myra.assistant.voice

import com.myra.assistant.data.memory.CorrectionTranscriptNormalizer

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
    val memoryExtractorInput: String get() = canonicalSemanticText
    val correctionParserInput: String get() = canonicalSemanticText
    val deleteParserInput: String get() = canonicalSemanticText
    val clarificationResolverInput: String get() = canonicalSemanticText
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
            val semantic = CorrectionTranscriptNormalizer.normalize(
                rawGeminiTranscript,
                formatted.display
            )
            val displayNames = formatted.protectedNameTokens.filter { name ->
                Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(semantic)
            }
            return FinalSemanticUserUtterance(
                sessionId = sessionId,
                turnId = turnId,
                utteranceId = "$sessionId:$turnId",
                rawGeminiTranscript = rawGeminiTranscript,
                canonicalSemanticText = semantic,
                displayText = semantic,
                canonicalNameTokens = formatted.protectedNameTokens,
                displayNameTokens = displayNames
            )
        }
    }
}
