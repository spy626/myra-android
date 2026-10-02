package com.myra.assistant.ui.workspace

import android.icu.text.Transliterator

/**
 * Typed Workspace CHAT: default Roman Hinglish, with a local script-only fallback.
 * No new provider call or translation. Never touches raw USER turns, executable
 * code, URLs, provider budget, permissions or the memory architecture.
 */
internal object WorkspaceHinglishReply {
    const val PROMPT_RULE =
        "LYRA CHAT LANGUAGE: Respond ONLY in natural Roman Hinglish: Hindi expressed " +
            "with ordinary Latin/English letters, English technical names when needed. " +
            "Never write Devanagari Hindi (U+0900–U+097F), Urdu script or formal " +
            "shuddh Hindi prose in an assistant reply. Say 'Bro, pehle app ke basic " +
            "features likho; abhi coding mat shuru karo', NOT Hindi-script words. " +
            "Use fluent, clear conversational grammar, not mechanical romanization. " +
            "Keep headings and bullets Roman Hinglish too. Respect the user's actual " +
            "request and length; never add a canned template. Preserve exact names, " +
            "user-quoted literals, filenames, URLs and code syntax; fenced code stays code."

    private val devanagari = Regex("[\\u0900-\\u097F]+")
    private val protectedInline = Regex(
        """(`+[^`\n]*`+|\]\(https?://[^\s)]+\)|https?://[^\s)]+)"""
    )
    private val fenceLine = Regex("""^\s{0,3}(`{3,}|~{3,})""")

    private val nativeTransliterator by lazy {
        runCatching {
            Transliterator.getInstance("Devanagari-Latin; Latin-ASCII")
        }.getOrElse {
            Transliterator.getInstance("Any-Latin; Latin-ASCII")
        }
    }

    private fun romanizeProse(line: String, transliterate: (String) -> String): String {
        if (!devanagari.containsMatchIn(line)) return line
        val result = StringBuilder()
        var cursor = 0
        protectedInline.findAll(line).forEach { match ->
            if (match.range.first > cursor) {
                result.append(devanagari.replace(
                    line.substring(cursor, match.range.first)
                ) { word -> transliterate(word.value) })
            }
            result.append(match.value)
            cursor = match.range.last + 1
        }
        if (cursor < line.length) {
            result.append(devanagari.replace(line.substring(cursor)) { word ->
                transliterate(word.value)
            })
        }
        return result.toString()
    }

    /** Pure injection point to test Markdown/literal protection without the Android ICU runtime. */
    internal fun normalizeWith(raw: String, transliterate: (String) -> String): String {
        if (!devanagari.containsMatchIn(raw)) return raw
        var marker: Char? = null
        var markerWidth = 0
        return raw.lines().joinToString("\n") { line ->
            val found = fenceLine.find(line)?.groupValues?.get(1)
            if (found != null) {
                if (marker == null) {
                    marker = found.first()
                    markerWidth = found.length
                } else if (found.first() == marker && found.length >= markerWidth) {
                    marker = null
                    markerWidth = 0
                }
                line
            } else if (marker != null) line
            else romanizeProse(line, transliterate)
        }
    }

    /** Last-resort local script conversion on a completed CHAT reply, no second AI request. */
    fun normalize(raw: String): String {
        if (!devanagari.containsMatchIn(raw)) return raw
        return normalizeWith(raw) { fragment ->
            runCatching { nativeTransliterator.transliterate(fragment) }
                .getOrDefault(fragment)
        }
    }
}