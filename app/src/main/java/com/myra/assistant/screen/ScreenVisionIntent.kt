package com.myra.assistant.screen

import java.util.Locale

enum class ScreenVisionIntent { ANALYZE, READ, EXPLAIN, FIND_ERROR, CONTROL_TARGET }
enum class InstantScreenQuery { OVERVIEW, CURRENT_APP }

object ScreenVisionIntentParser {
    private val screenSignal = Regex(
        """\b(?:screen|display|visible|dikh|dekh|website|page|thumbnail|analytics|code|error|button|card|item|result|video|this|that|looking)\b""",
        RegexOption.IGNORE_CASE
    )
    private val analyzeSignal = Regex(
        """\b(?:what(?:'s| is)|how|kya|read|summari[sz]e|explain|analy[sz]e|dikha|dekh|looking|error)\b""",
        RegexOption.IGNORE_CASE
    )
    private val controlSignal = Regex(
        """\b(?:tap|click|open|chalao|khol|press)\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): ScreenVisionIntent? {
        val normalized = text.lowercase(Locale.ROOT).trim()
        if (!screenSignal.containsMatchIn(normalized)) return null
        if (controlSignal.containsMatchIn(normalized)) return ScreenVisionIntent.CONTROL_TARGET
        if (!analyzeSignal.containsMatchIn(normalized)) return null
        return when {
            Regex("\\b(?:read|padh)\\b").containsMatchIn(normalized) -> ScreenVisionIntent.READ
            Regex("\\b(?:error|problem|issue)\\b").containsMatchIn(normalized) -> ScreenVisionIntent.FIND_ERROR
            Regex("\\b(?:explain|samjha)\\b").containsMatchIn(normalized) -> ScreenVisionIntent.EXPLAIN
            else -> ScreenVisionIntent.ANALYZE
        }
    }

    /** Conservative enough for streamed ASR: it cannot fire for a generic mention of a video. */
    fun parseStableQuery(text: String): ScreenVisionIntent? {
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val explicit = Regex(
            """\b(?:what is on (?:my|the) screen|what do you see|kya dikh raha|kya dikh rha|screen (?:par|pe|mein|me) kya|tumhe kya dikh|analy[sz]e (?:my |the )?screen|screen dekho)\b""",
            RegexOption.IGNORE_CASE
        )
        return if (explicit.containsMatchIn(normalized)) ScreenVisionIntent.ANALYZE else null
    }

    /** Only questions answerable safely from the pre-analyzed accessibility summary. */
    fun parseInstantQuery(text: String): InstantScreenQuery? {
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return when {
            Regex("\\b(?:which app is open|what app is open|kaunsi app (?:open|khuli))\\b").containsMatchIn(normalized) ->
                InstantScreenQuery.CURRENT_APP
            Regex("\\b(?:what do you see|what is on (?:my|the) screen|kya dikh raha|kya dikh rha|screen (?:par|pe|mein|me) kya)\\b")
                .containsMatchIn(normalized) -> InstantScreenQuery.OVERVIEW
            else -> null
        }
    }
}
