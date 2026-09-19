package com.myra.assistant.ui.workspace

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Small native, non-HTML formatter. No WebView, links, script execution or network content. */
internal object WorkspaceMarkdownText {
    private val heading = Regex("^\\s{0,3}(#{1,3})\\s+(.+?)\\s*$")
    private val bold = Regex("\\*\\*(.+?)\\*\\*")
    private val italic = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")
    private val code = Regex("`([^`\\n]+)`")

    fun render(raw: String): CharSequence {
        val result = SpannableStringBuilder()
        raw.lines().forEachIndexed { index, original ->
            if (index != 0) result.append('\n')
            val match = heading.matchEntire(original)
            val line = SpannableStringBuilder(match?.groupValues?.get(2) ?: original)
            fun style(pattern: Regex, makeSpan: () -> Any) {
                pattern.findAll(line.toString()).toList().asReversed().forEach { item ->
                    val value = item.groupValues[1]
                    val begin = item.range.first
                    line.replace(begin, item.range.last + 1, value)
                    val span = makeSpan()
                    when (span) {
                        is StyleSpan -> line.setSpan(span, begin, begin + value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        is TypefaceSpan -> line.setSpan(span, begin, begin + value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            style(bold) { StyleSpan(Typeface.BOLD) }
            style(italic) { StyleSpan(Typeface.ITALIC) }
            style(code) { TypefaceSpan("monospace") }
            if (match != null && line.isNotEmpty()) {
                line.setSpan(StyleSpan(Typeface.BOLD), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                line.setSpan(RelativeSizeSpan(if (match.groupValues[1].length == 1) 1.35f else 1.17f),
                    0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            result.append(line)
        }
        return result
    }
}
