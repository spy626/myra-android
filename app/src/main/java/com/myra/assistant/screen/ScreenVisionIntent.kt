package com.myra.assistant.screen

import java.util.Locale

enum class ScreenVisionIntent { ANALYZE, READ, EXPLAIN, FIND_ERROR, CONTROL_TARGET }

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
}
