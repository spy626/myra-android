package com.myra.assistant.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.util.Locale

data class BrowserSearchRequest(val query: String)

object BrowserSearchRequestParser {
    fun parse(raw: String): BrowserSearchRequest? {
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        val patterns = listOf(
            Regex("^(?:please )?(?:google|chrome|browser)(?: mein| me| par)? (?:search|find|dhundo|dhoondo|khojo) (?:karo )?(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:search|find) (?:google|the web|web) (?:for )?(.+)$", RegexOption.IGNORE_CASE)
        )
        val query = patterns.firstNotNullOfOrNull { it.matchEntire(normalized)?.groupValues?.get(1) }
            ?.trim()?.trimEnd('.', '?')?.takeIf { it.length in 2..300 }
        return query?.let(::BrowserSearchRequest)
    }
}

data class BrowserSearchDispatch(val accepted: Boolean, val expectedPackage: String?, val reason: String)

/** General browser capability. It opens a standards-based search URL; observation and result
 * interpretation remain separate plan steps owned by UnifiedLyraAgent. */
class BrowserSearchTool(private val context: Context) {
    fun execute(request: BrowserSearchRequest): BrowserSearchDispatch {
        val encoded = URLEncoder.encode(request.query, Charsets.UTF_8.name())
        val uri = Uri.parse("https://www.google.com/search?q=$encoded")
        val chrome = "com.android.chrome"
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        val expected = if (context.packageManager.getLaunchIntentForPackage(chrome) != null) chrome else null
        if (expected != null) intent.setPackage(expected)
        return try {
            context.startActivity(intent)
            BrowserSearchDispatch(true, expected, "dispatched")
        } catch (_: Exception) {
            BrowserSearchDispatch(false, expected, "browser_unavailable")
        }
    }
}
