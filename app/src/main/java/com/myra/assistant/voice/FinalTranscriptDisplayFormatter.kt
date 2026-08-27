package com.myra.assistant.voice

import java.text.Normalizer

/**
 * Formats Gemini's stable FINAL input transcript for the red user bubble.
 *
 * This is deliberately separate from [RomanHinglishFormatter]. That legacy formatter
 * repairs memory/command transcripts and must not fuzzy-canonicalize ordinary display
 * text or proper names.
 */
object FinalTranscriptDisplayFormatter {
    data class Result(
        val transliterated: String,
        val display: String,
        val latinWordsPreserved: Boolean,
        val properNameProtected: Boolean,
        val protectedNameTokens: List<String>,
        val appliedRuleIds: List<String>
    )

    private val tokenPattern = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’-][\\p{L}\\p{M}\\p{N}]+)*|[^\\s]")
    private val devanagari = Regex("[\\u0900-\\u097F]")
    private val latin = Regex("[A-Za-z]")

    // Small, conservative readability lexicon. Entries are accepted only when the
    // original FINAL Hindi token is an exact match; there is no phonetic/fuzzy rewrite.
    private val stableWords = mapOf(
        "अब" to "ab", "मुझे" to "mujhe", "बताओ" to "batao", "आज" to "aaj",
        "हम" to "hum", "किस" to "kis", "बारे" to "baare", "में" to "mein",
        "बात" to "baat", "करें" to "karein", "रुको" to "ruko", "मेरी" to "meri",
        "सुनो" to "suno", "मेरा" to "mera", "मेरे" to "mere", "बेस्ट" to "best",
        "फ्रेंड" to "friend", "है" to "hai", "नहीं" to "nahi", "क्या" to "kya",
        "जानते" to "jaante", "हो" to "ho", "दोस्त" to "dost", "कौन" to "kaun"
    )

    // Exact-script protection keeps these two distinct. Display formatting never asks
    // memory similarity/canonicalization to choose between them.
    private val protectedNames = mapOf("करीमा" to "Karima", "करीम" to "Kareem")

    fun format(rawFinal: String, transliterateToken: (String) -> String): Result {
        val rules = linkedSetOf<String>()
        var latinPreserved = false
        var nameProtected = false
        val protectedNameTokens = mutableListOf<String>()
        val rendered = mutableListOf<String>()
        val transliteratedTokens = mutableListOf<String>()

        tokenPattern.findAll(rawFinal.trim()).forEach { match ->
            val token = match.value
            val literal = when {
                devanagari.containsMatchIn(token) -> transliterateToken(token).ifBlank { token }
                else -> token
            }
            transliteratedTokens += normalizeLiteral(literal)

            val displayToken = when {
                protectedNames.containsKey(token) -> {
                    nameProtected = true
                    rules += "protected_hindi_name"
                    protectedNames.getValue(token).also(protectedNameTokens::add)
                }
                stableWords.containsKey(token) -> {
                    rules += "exact_hindi_readability"
                    stableWords.getValue(token)
                }
                latin.containsMatchIn(token) && !devanagari.containsMatchIn(token) -> {
                    latinPreserved = true
                    rules += "preserve_latin_token"
                    token
                }
                token == "।" -> "."
                devanagari.containsMatchIn(token) -> normalizeLiteral(literal)
                else -> token
            }
            rendered += displayToken
        }

        if (rendered.size > 3 && rendered.take(3).map { it.lowercase() } == listOf("ab", "mujhe", "batao")) {
            rendered.add(3, ",")
            rules += "opening_batao_comma"
        }

        val display = capitalizeSentence(joinTokens(rendered))
        return Result(
            transliterated = joinTokens(transliteratedTokens),
            display = display,
            latinWordsPreserved = latinPreserved,
            properNameProtected = nameProtected,
            protectedNameTokens = protectedNameTokens,
            appliedRuleIds = rules.toList()
        )
    }

    private fun normalizeLiteral(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace("'", "")
        .replace("’", "")
        .trim()

    private fun joinTokens(tokens: List<String>): String = tokens.fold("") { text, token ->
        when {
            text.isEmpty() -> token
            token.matches(Regex("[,.!?]")) -> text + token
            else -> "$text $token"
        }
    }.replace(Regex("\\s+"), " ").trim()

    private fun capitalizeSentence(value: String): String =
        value.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
