package com.myra.assistant.ai

import com.myra.assistant.model.AppCommand
import java.util.Locale

object CommandParser {
    private val appAliases = linkedMapOf(
        "google maps" to "Google Maps", "play store" to "Play Store", "google pay" to "Google Pay",
        "व्हाट्सएप" to "WhatsApp", "वॉट्सऐप" to "WhatsApp", "whatsapp" to "WhatsApp",
        "इंस्टाग्राम" to "Instagram", "instagram" to "Instagram",
        "यूट्यूब" to "YouTube", "youtube" to "YouTube",
        "स्नैपचैट" to "Snapchat", "snapchat" to "Snapchat",
        "टेलीग्राम" to "Telegram", "telegram" to "Telegram",
        "फेसबुक" to "Facebook", "facebook" to "Facebook",
        "क्रोम" to "Chrome", "chrome" to "Chrome", "जीमेल" to "Gmail", "gmail" to "Gmail",
        "मैप्स" to "Maps", "maps" to "Maps", "स्पॉटिफाई" to "Spotify", "spotify" to "Spotify",
        "नेटफ्लिक्स" to "Netflix", "netflix" to "Netflix", "ट्विटर" to "Twitter", "twitter" to "Twitter",
        "सेटिंग्स" to "Settings", "settings" to "Settings", "फोनपे" to "PhonePe", "phonepe" to "PhonePe",
        "जीपे" to "GPay", "gpay" to "GPay", "पेटीएम" to "Paytm", "paytm" to "Paytm",
        "फ्लिपकार्ट" to "Flipkart", "flipkart" to "Flipkart", "अमेज़न" to "Amazon", "amazon" to "Amazon",
        "डिस्कॉर्ड" to "Discord", "discord" to "Discord", "लिंक्डइन" to "LinkedIn", "linkedin" to "LinkedIn"
    )

    private val openAction = Regex(
        "(?:\\b(?:open|opening|launch|start|get|kholo|kholo|khol|khul|kholna|khol\\s+do|khol\\s+dena|open\\s+karo|open\\s+karna|open\\s+kar\\s+do|open\\s+kar\\s+sakti\\s+ho|chalao|chalu\\s+karo|dikhao|check\\s+karna|check\\s+karna\\s+hai)\\b|खोलो|खोलना|खोल\\s+दो|खोल\\s+देना|ओपन\\s+करो|ओपन\\s+करना|ओपन\\s+कर\\s+दो|चलाओ|चालू\\s+करो|दिखाओ|देखना)"
    )
    private val closeAction = Regex(
        "(?:\\b(?:close|close\\s+karo|close\\s+karna|band\\s+karo|band\\s+kar\\s+do|band\\s+karna)\\b|बंद\\s+करो|बंद\\s+कर\\s+दो|बंद\\s+करना|क्लोज\\s+करो)"
    )

    fun parse(raw: String): AppCommand? {
        val text = normalize(raw)
        if (text.isBlank()) return null
        if (closeAction.containsMatchIn(text)) return AppCommand.CloseCurrentApp(findKnownApp(text))
        val knownApp = findKnownApp(text)
        if (openAction.containsMatchIn(text)) {
            knownApp?.let { return AppCommand.OpenApp(it) }
            extractSimpleAppName(text)?.let { return AppCommand.OpenApp(it) }
        }

        // Gemini input transcription often turns Hindi "kholo" into short words such as
        // "get", "hello" or drops it completely. A short utterance naming one known app is
        // still a command; longer conversational mentions are deliberately not executed.
        if (knownApp != null && looksLikeShortAppCommand(text)) return AppCommand.OpenApp(knownApp)
        return null
    }

    private fun looksLikeShortAppCommand(text: String): Boolean {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size > 7) return false
        val conversational = Regex("\\b(?:cannot|cant|nahi|nahin|problem|message|video|about|like|pasand|mein\\s+message)\\b")
        return !conversational.containsMatchIn(text)
    }

    private fun findKnownApp(text: String): String? = appAliases.entries.firstOrNull { (alias, _) ->
        Regex("(?:^|\\s)${Regex.escape(alias)}(?:$|\\s)").containsMatchIn(text)
    }?.value

    private fun extractSimpleAppName(text: String): String? {
        val patterns = listOf(
            Regex("^(?:please\\s+)?(?:open|launch|start)\\s+([\\p{L}\\p{N} ]{1,35})$"),
            Regex("^([\\p{L}\\p{N} ]{1,35})\\s+(?:kholo|khol\\s+do|open\\s+karo|open\\s+kar\\s+do|chalao)$")
        )
        return patterns.firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) }
            ?.replace(Regex("^(?:the|my|mera|meri)\\s+"), "")
            ?.replace(Regex("\\s+(?:app|application)$"), "")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
}
