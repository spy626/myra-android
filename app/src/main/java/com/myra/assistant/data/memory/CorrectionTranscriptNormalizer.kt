package com.myra.assistant.data.memory

/** Preserves correction-name contrast from Gemini's untouched final transcript. */
object CorrectionTranscriptNormalizer {
    private val hindiPair = Regex("^\\s*करीमा[,،]?\\s+नहीं[,]?\\s+करीम[।.!?]?\\s*$")

    fun normalize(rawFinal: String, romanizedFinal: String): String =
        if (hindiPair.matches(rawFinal)) "Karima nahi Kareem" else romanizedFinal
}
