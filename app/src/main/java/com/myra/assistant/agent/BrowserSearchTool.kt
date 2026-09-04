package com.myra.assistant.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.util.Locale

enum class SearchDestination { YOUTUBE, BROWSER }

data class BrowserSearchRequest(
    val query: String,
    val explicitDestination: SearchDestination? = null
)

object SearchDestinationResolver {
    private val browserPackages = setOf(
        "com.android.chrome",
        "com.google.android.googlequicksearchbox",
        "org.mozilla.firefox",
        "com.microsoft.emmx"
    )

    fun resolve(
        request: BrowserSearchRequest,
        currentPackage: String?,
        activeTaskPackage: String?
    ): SearchDestination {
        request.explicitDestination?.let { return it }
        val currentIsAssistantTransition = currentPackage in setOf("com.myra.assistant", "com.android.systemui")
        val contextualPackage = when {
            currentPackage == null || currentIsAssistantTransition -> activeTaskPackage
            activeTaskPackage == currentPackage -> activeTaskPackage
            else -> currentPackage // Actual external foreground wins over stale task state.
        }
        return if (contextualPackage == "com.google.android.youtube") SearchDestination.YOUTUBE
        else SearchDestination.BROWSER
    }

    fun isCompatibleBrowser(packageName: String?): Boolean = packageName in browserPackages
}

object BrowserSearchRequestParser {
    fun parse(raw: String): BrowserSearchRequest? {
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        val explicit = listOf(
            SearchDestination.YOUTUBE to Regex("^(?:please )?(?:youtube|यूट्यूब)(?: mein| me| par| pe| में| पर)? (?:search|find|dhundo|dhoondo|khojo|सर्च|ढूंढो|खोजो) (?:karo |करो )?(.+)$", RegexOption.IGNORE_CASE),
            SearchDestination.YOUTUBE to Regex("^(?:please )?(?:youtube|यूट्यूब)(?: mein| me| par| pe| में| पर)? (.+?) (?:search|find|dhundo|dhoondo|khojo|सर्च|ढूंढो|खोजो)(?: karo| kar do| करो| कर दो)?$", RegexOption.IGNORE_CASE),
            SearchDestination.BROWSER to Regex("^(?:please )?(?:google|chrome|browser|web)(?: mein| me| par| pe)? (?:search|find|dhundo|dhoondo|khojo) (?:karo )?(.+)$", RegexOption.IGNORE_CASE),
            SearchDestination.BROWSER to Regex("^(?:search|find) (?:google|the web|web) (?:for )?(.+)$", RegexOption.IGNORE_CASE)
        )
        explicit.firstNotNullOfOrNull { (destination, pattern) ->
            pattern.matchEntire(normalized)?.groupValues?.get(1)?.let { destination to it }
        }?.let { (destination, value) ->
            return cleanQuery(value)?.let { BrowserSearchRequest(it, destination) }
        }
        val generic = listOf(
            Regex("^(?:please )?(?:search|find|dhundo|dhoondo|khojo|सर्च|ढूंढो|खोजो)(?: karo| kar do| करो| कर दो)? (.+)$", RegexOption.IGNORE_CASE),
            Regex("^(.+?) (?:search|find|dhundo|dhoondo|khojo|सर्च|ढूंढो|खोजो)(?: karo| kar do| करो| कर दो)$", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.matchEntire(normalized)?.groupValues?.get(1) }
        return cleanQuery(generic)?.let(::BrowserSearchRequest)
    }

    private fun cleanQuery(value: String?): String? = value?.trim()?.trimEnd('.', '?')
        ?.takeIf { it.length in 2..300 }
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
