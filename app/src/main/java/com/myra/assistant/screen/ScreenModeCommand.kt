package com.myra.assistant.screen

import java.text.Normalizer
import java.util.Locale

enum class ScreenModeCommand { ON, OFF }

object ScreenModeCommandParser {
    fun parse(raw: String): ScreenModeCommand? = when (normalize(raw)) {
        "screen mode on karo", "screen sharing on karo", "screen vision on karo",
        "screen mode chalu karo", "screen sharing chalu karo",
        "स्क्रीन मोड ऑन करो", "स्क्रीन शेयरिंग ऑन करो" -> ScreenModeCommand.ON
        "screen mode off karo", "screen sharing off karo", "screen vision off karo",
        "screen sharing band karo", "screen mode band karo",
        "स्क्रीन शेयरिंग बंद करो", "स्क्रीन मोड बंद करो" -> ScreenModeCommand.OFF
        else -> null
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
}
