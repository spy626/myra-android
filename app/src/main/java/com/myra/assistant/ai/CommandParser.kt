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
        if (isWhatsAppMessageQuery(text)) return AppCommand.QueryWhatsAppMessages
        extractWhatsAppReply(text)?.let { return it }
        extractDeepResearch(text)?.let { return it }
        if (isRepeatYouTubeSearch(text)) return AppCommand.RepeatYouTubeSearch
        extractYouTubeSearch(text)?.let { return AppCommand.SearchYouTube(it) }
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

    private fun extractWhatsAppReply(text: String): AppCommand.ReplyWhatsApp? {
        val reply = "(?:reply|replay|re\\s+ply|jawab|रिप्लाई|रिप्लाय|जवाब)"
        val action = "(?:do|karo|bhejo|send\\s+karo|भेजो|करो|दो)"
        val filler = "(?:bolke|bol\\s+kar|likh\\s+ke|likh\\s+kar|बोलके|बोल\\s+कर|लिख\\s+के|लिख\\s+कर)"
        val named = listOf(
            Regex("^(.+?)\\s+ko\\s+(?:whatsapp\\s+)?$reply\\s+(?:$action)?\\s*(.+)$"),
            Regex("^(.+?)\\s+ko\\s+(.+?)\\s+$filler\\s+(?:(?:message|msg|मैसेज)\\s+)?(?:$reply\\s+(?:$action)?|bhejo|send\\s+karo|भेजो)$"),
            Regex("^(.+?)\\s+ko\\s+(.+?)\\s+(?:message|msg|मैसेज)\\s+(?:bhejo|send\\s+karo|भेजो)$")
        )
        named.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(text)?.let { AppCommand.ReplyWhatsApp(it.groupValues[1].trim(), cleanReplyText(it.groupValues[2])) }
        }?.takeIf { it.message.isNotBlank() }?.let { return it }
        val contextual = listOf(
            Regex("^$reply\\s+(?:$action)?\\s*(.+)$"),
            Regex("^(.+?)\\s+$filler\\s+(?:(?:message|msg|मैसेज)\\s+)?(?:$reply\\s+(?:$action)?|bhejo|send\\s+karo|भेजो)$"),
            Regex("^(.+?)\\s+(?:message|msg|मैसेज)\\s+(?:bhejo|send\\s+karo|भेजो)$"),
            Regex("^(.+?)\\s+(?:bhejo|send\\s+karo|भेजो)$")
        ).firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1)?.let(::cleanReplyText) }
        contextual?.takeIf { it.isNotBlank() }?.let { return AppCommand.ReplyWhatsApp(null, it) }
        val messaging = Regex("(?:\\b(?:reply|replay|jawab|bhejo|send\\s+karo)\\b|रिप्लाई|जवाब|मैसेज|भेजो)")
        return if (messaging.containsMatchIn(text)) AppCommand.ReplyWhatsApp(null, "") else null
    }

    private fun isWhatsAppMessageQuery(text: String): Boolean {
        val whatsapp = Regex("(?:whatsapp|व्हाट्सएप|वॉट्सऐप)")
        val message = Regex("(?:message|msg|मैसेज|संदेश)")
        val question = Regex("(?:aaya|aya|aayi|hai|kaun|kisne|who|what|check|batao|आया|आई|कौन|किसने|बताओ)")
        return whatsapp.containsMatchIn(text) && message.containsMatchIn(text) && question.containsMatchIn(text)
    }

    private fun cleanReplyText(value: String): String {
        var cleaned = value.trim().trim('"', '\'', '.', ',')
        val suffix = Regex("\\s+(?:bolke|bol\\s+kar|likh\\s+ke|likh\\s+kar|message|msg|send\\s+karo|bhejo|reply\\s+(?:do|karo)|jawab\\s+(?:do|karo)|बोलके|लिख\\s+के|मैसेज|भेजो)$")
        while (suffix.containsMatchIn(cleaned)) cleaned = suffix.replace(cleaned, "").trim()
        return cleaned.trim('"', '\'', '.', ',')
    }

    /**
     * Commands allowed while external media is playing without requiring a wake word.
     * Keep this deliberately strict so ordinary dialogue from a video is ignored.
     */
    fun parseDirectMediaControl(raw: String): AppCommand? {
        val text = normalize(raw)
        val close = Regex(
            "^(?:(?:and|aur|और)\\s+)?(?:(?:youtube|यूट्यूब)\\s+)?(?:close|close\\s+karo|close\\s+kar\\s+do|band\\s+karo|band\\s+kar\\s+do|बंद\\s+करो|बंद\\s+कर\\s+दो|क्लोज\\s+करो)(?:\\s+(?:youtube|यूट्यूब))?$"
        )
        return if (close.matches(text)) AppCommand.CloseCurrentApp(findKnownApp(text)) else null
    }

    private fun extractDeepResearch(text: String): AppCommand.DeepResearch? {
        val trigger = Regex("(?:deep\\s+(?:research|search)|in-depth\\s+(?:research|search)|गहरी\\s+(?:रिसर्च|सर्च)|डीप\\s+(?:रिसर्च|सर्च))")
        if (!trigger.containsMatchIn(text)) return null
        val intent = Regex("(?:karo|karna|kar\\s+do|karke|batao|dhundo|dhoondo|find|do|can\\s+you|sakti\\s+ho|sakta\\s+hai|करो|करना|कर\\s+दो|करके|बताओ|ढूंढो)")
        if (!text.startsWith("deep ") && !text.startsWith("in depth ") && !intent.containsMatchIn(text)) return null
        val filler = Regex("(?:please|kya|mere\\s+liye|kar\\s+sakti\\s+ho|kar\\s+sakta\\s+hai|sakti\\s+ho|sakta\\s+hai|can\\s+you|karo|karna|kar\\s+do|karke|batao|dhundo|dhoondo|find|about|on|please|क्या|कर\\s+सकती\\s+हो|कर\\s+सकता\\s+है|करो|करना|कर\\s+दो|करके|बताओ|ढूंढो)")
        val query = trigger.replace(text, " ").let { filler.replace(it, " ") }
            .replace(Regex("\\s+"), " ").trim().trim('?', '.', ',')
            .takeIf { it.length >= 3 }
        return AppCommand.DeepResearch(query)
    }

    private fun extractYouTubeSearch(text: String): String? {
        val action = "(?:search(?:\\s+karo|\\s+kar\\s+do)?|find|dhundo|dhoondo|khojo|सर्च(?:\\s+करो|\\s+कर\\s+दो)?|ढूंढो|खोजो)"
        val youtube = "(?:youtube|यूट्यूब)"
        val place = "(?:mein|me|par|pe|में|पर)"
        val prefix = "(?:(?:please|ek\\s+(?:aur\\s+)?baar|ek\\s+bar|phir\\s+se|fir\\s+se|dobara|again|एक\\s+(?:और\\s+)?बार|फिर\\s+से|दोबारा)\\s+)?"
        val repeatWord = "(?:(?:phir\\s+se|fir\\s+se|dobara|again|फिर\\s+से|दोबारा)\\s+)?"
        val patterns = listOf(
            Regex("^$prefix$youtube\\s+$place\\s+$repeatWord(.+?)\\s+$action$"),
            Regex("^$prefix$youtube\\s+$place\\s+$repeatWord$action\\s+(.+?)$"),
            Regex("^$prefix$youtube\\s+(.+?)\\s+$action$"),
            Regex("^$prefix$youtube\\s+$action\\s+(.+?)$"),
            Regex("^(.+?)\\s+$youtube\\s+$place\\s+$action$"),
            Regex("^$action\\s+(.+?)\\s+(?:on|in|$place)\\s+$youtube$"),
            Regex("^$action\\s+(.+?)\\s+$youtube$"),
            Regex("^(?:phir\\s+se|fir\\s+se|dobara|again)\\s+(.+?)\\s+$action$"),
            // When YouTube is already open, people naturally omit its name:
            // "Lols Gaming search karo". Treat a query followed by an explicit
            // search verb as a YouTube search instead of degrading to OpenApp.
            Regex("^(.+?)\\s+$action$")
        )
        return patterns.firstNotNullOfOrNull { it.matchEntire(text)?.groupValues?.get(1) }
            ?.replace(Regex("^(?:for|the|video|channel)\\s+"), "")
            ?.replace(Regex("\\s+(?:video|channel)$"), "")
            ?.replace(Regex("^(?:please|youtube\\s+(?:mein|me|par|pe)|यूट्यूब\\s+(?:में|पर))\\s+"), "")
            ?.trim()?.takeIf { it.length in 2..80 }
    }

    private fun isRepeatYouTubeSearch(text: String): Boolean {
        val explicitReference = Regex("(?:same\\s+(?:channel|search)|wahi\\s+(?:channel|search)|usi\\s+channel|phir\\s+se\\s+wahi|fir\\s+se\\s+wahi|वही\\s+(?:चैनल|सर्च)|उसी\\s+चैनल)")
        val action = Regex("(?:open|kholo|khol\\s+do|search|find|dhundo|dhoondo|khojo|ओपन|खोलो|खोल\\s+दो|सर्च|ढूंढो|खोजो)")
        if (explicitReference.containsMatchIn(text) && action.containsMatchIn(text)) return true

        // Live transcription sometimes turns "phir se" into "police se". These short
        // commands are contextual repeats; AppActionExecutor safely refuses them when no
        // previous YouTube query has been stored.
        return Regex("^(?:(?:phir|fir|police)\\s+se|dobara|again|फिर\\s+से|दोबारा)\\s+(?:open|kholo|khol\\s+do|search|ओपन|खोलो|सर्च)(?:\\s+karo)?$").matches(text)
    }

    private fun looksLikeShortAppCommand(text: String): Boolean {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size > 7) return false
        // These endings indicate an incomplete streamed instruction, for example
        // "YouTube mein ..." before "search karo Lols Gaming" arrives. Waiting for
        // the next transcription chunk prevents an accidental plain app launch.
        if (Regex("(?:\\b(?:mein|me|par|pe|on|in|search|find|dhundo|dhoondo|khojo)\\b|में|पर|सर्च|ढूंढो|खोजो)$").containsMatchIn(text)) return false
        val conversational = Regex("\\b(?:cannot|cant|nahi|nahin|problem|message|video|about|like|pasand|mein\\s+message)\\b")
        return !conversational.containsMatchIn(text)
    }

    private fun findKnownApp(text: String): String? = appAliases.entries.firstOrNull { (alias, _) ->
        Regex("(?:^|\\s)${Regex.escape(alias)}(?:$|\\s)").containsMatchIn(text)
    }?.value

    private fun extractSimpleAppName(text: String): String? {
        val contextualWords = Regex("(?:same\\s+channel|wahi\\s+channel|usi\\s+channel|phir\\s+se|fir\\s+se|police\\s+se|वही\\s+चैनल|उसी\\s+चैनल)")
        if (contextualWords.containsMatchIn(text)) return null
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
