package com.myra.assistant.ui.workspace

import java.util.Locale

/**
 * Public, read-only third-party app download intent. This is not an APK build request
 * or authority to install software. Never infer a publisher from model text.
 */
internal object WorkspaceAppDownloadIntent {
    data class Request(val appName: String) {
        init { require(appName.length in 2..90) }
    }

    private val token = Regex("""[\p{L}\p{N}][\p{L}\p{N}._+-]*""")
    private val productType = Regex("""(?iu)\bapp\b""")
    private val requestCue = Regex("""(?iu)\b(?:download|install|links?|urls?|bhejo|bhej|send|give)\b""")
    private val prohibited = Regex(
        """(?iu)https?://|\b(?:password|otp|api[ -]?key|token|secret|""" +
            """fix|edit|modify|implement|create|update|remove|build\s*#?\d+)\b"""
    )
    private val selfBuild = Regex(
        """(?iu)\b(?:github\s+actions|workflow|artifact|build\s*#?\d+|lyra|myra|myra-android)\b"""
    )
    private val noise = setOf(
        "bro", "bhai", "please", "mujhe", "ye", "yeh", "vo", "woh", "uska",
        "ka", "ki", "ke", "ko", "mera", "meri", "koi", "the", "a", "an",
        "give", "me", "send", "share", "link", "links", "url", "urls",
        "download", "downloads", "install", "installation", "for", "of",
        "android", "iphone", "ios", "play", "store", "google", "apple", "official",
    )

    private fun nameBeforeApp(text: String): String? {
        val match = productType.find(text) ?: return null
        val before = token.findAll(text.substring(0, match.range.first))
            .map { it.value }.filterNot { it.lowercase(Locale.ROOT) in noise }.toList()
        val words = before.takeLast(3)
        return words.joinToString(" ").takeIf { it.length in 2..90 }
    }

    fun decide(
        turn: String,
        preceding: List<WorkspaceConversationStore.Message> = emptyList(),
    ): Request? {
        val text = turn.trim().replace(Regex("""\s+"""), " ")
        if (text.length !in 2..260 || prohibited.containsMatchIn(text) ||
            selfBuild.containsMatchIn(text)) return null
        if (requestCue.containsMatchIn(text) && productType.containsMatchIn(text)) {
            return nameBeforeApp(text)?.let(::Request)
        }
        // Bounded repair: "Cantina app ka bro" only after immediately preceding
        // explicit app-download request, with same app identity; no older chat authority.
        val last = preceding.lastOrNull()
        val previous = preceding.getOrNull(preceding.size - 2)
        if (last?.role != "assistant" || previous?.role != "user") return null
        val pending = decide(previous.text) ?: return null
        val reminder = nameBeforeApp(text) ?: return null
        if (text.length > 90 || !productType.containsMatchIn(text) ||
            !compact(reminder).equals(compact(pending.appName), ignoreCase = true)) return null
        return pending
    }

    internal fun compact(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]"""), "")

    internal fun nameMatches(requested: String, found: String): Boolean {
        val a = compact(requested)
        val b = compact(found)
        if (a.length < 2 || b.length < 2) return false
        return a == b || (
            (a.startsWith(b) || b.startsWith(a)) &&
                minOf(a.length, b.length) >= 4 &&
                minOf(a.length, b.length) * 100 >= maxOf(a.length, b.length) * 65
            )
    }
}
