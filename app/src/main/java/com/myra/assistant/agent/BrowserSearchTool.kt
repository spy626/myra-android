package com.myra.assistant.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.myra.assistant.diagnostics.VoicePipelineLogger
import java.net.URLEncoder
import java.util.Locale

enum class SearchDestination { YOUTUBE, BROWSER }

enum class BrowserSearchExecutor { CURRENT_GOOGLE_APP, CURRENT_BROWSER, GENERIC_WEB }

data class SearchResolution(
    val destination: SearchDestination,
    val reason: String,
    val selectedExecutor: BrowserSearchExecutor? = null,
    val targetPackage: String? = null
)

data class BrowserSearchRequest(
    val query: String,
    val explicitDestination: SearchDestination? = null
)

/** Search is destination-sensitive, so streamed/partial transcripts may only
 * nominate it as a candidate. Execution is authorized at the final turn. */
object SearchExecutionPolicy {
    fun mayExecute(authoritativeFinalTranscript: Boolean): Boolean = authoritativeFinalTranscript
}

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
    ): SearchDestination = resolveDetailed(request, currentPackage, activeTaskPackage).destination

    fun resolveDetailed(
        request: BrowserSearchRequest,
        currentPackage: String?,
        activeTaskPackage: String?
    ): SearchResolution {
        if (request.explicitDestination == SearchDestination.YOUTUBE) {
            return SearchResolution(SearchDestination.YOUTUBE, "explicit_destination")
        }
        val currentIsAssistantTransition = currentPackage in setOf("com.myra.assistant", "com.android.systemui")
        val contextualPackage = when {
            currentPackage == null || currentIsAssistantTransition -> activeTaskPackage
            activeTaskPackage == currentPackage -> activeTaskPackage
            else -> currentPackage // Actual external foreground wins over stale task state.
        }
        if (contextualPackage == "com.google.android.youtube" && request.explicitDestination == null) {
            return SearchResolution(SearchDestination.YOUTUBE, "current_youtube_context", targetPackage = contextualPackage)
        }
        if (contextualPackage == "com.google.android.googlequicksearchbox") {
            return SearchResolution(SearchDestination.BROWSER, "current_google_search_context",
                BrowserSearchExecutor.CURRENT_GOOGLE_APP, contextualPackage)
        }
        if (isCompatibleBrowser(contextualPackage)) {
            return SearchResolution(SearchDestination.BROWSER, "current_browser_context",
                BrowserSearchExecutor.CURRENT_BROWSER, contextualPackage)
        }
        return SearchResolution(SearchDestination.BROWSER, "generic_web_fallback",
            BrowserSearchExecutor.GENERIC_WEB)
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
            Regex("^(?:please )?(?:search|find)(?: karo| kar do)?(?: for)? (.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:please )?(?:dhundo|dhoondo|khojo|सर्च|ढूंढो|खोजो)(?: karo| kar do| करो| कर दो)? (.+)$", RegexOption.IGNORE_CASE),
            Regex("^(.+?) (?:search|find|dhundo|dhoondo|khojo|सर्च|ढूंढो|खोजो)(?: karo| kar do| करो| कर दो)$", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.matchEntire(normalized)?.groupValues?.get(1) }
        return cleanQuery(generic)?.let(::BrowserSearchRequest)
    }

    private fun cleanQuery(value: String?): String? = value?.trim()?.trimEnd('.', '?')
        ?.takeIf { it.length in 2..300 }
}

data class BrowserSearchDispatch(val accepted: Boolean, val expectedPackage: String?, val reason: String)

enum class SearchVerification { SUCCESS, UNKNOWN, FAILURE }

object SearchTaskResultPolicy {
    fun maySpeakFailure(verification: SearchVerification): Boolean = verification == SearchVerification.FAILURE
    fun ordinaryModelMayReportResult(completionState: TaskCompletionState?): Boolean = completionState == null
}

object BrowserSearchVerificationPolicy {
    fun verify(
        request: BrowserSearchRequest,
        resolution: SearchResolution,
        foregroundPackage: String?,
        visibleLabels: List<String>
    ): SearchVerification {
        if (foregroundPackage == null) return SearchVerification.UNKNOWN
        if (resolution.targetPackage != null && foregroundPackage != resolution.targetPackage) {
            return SearchVerification.UNKNOWN
        }
        if (!SearchDestinationResolver.isCompatibleBrowser(foregroundPackage)) return SearchVerification.UNKNOWN
        val visible = visibleLabels.joinToString(" ").lowercase(Locale.ROOT)
        val meaningfulQueryTokens = request.query.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= 2 }
        return if (meaningfulQueryTokens.isNotEmpty() && meaningfulQueryTokens.any(visible::contains)) {
            SearchVerification.SUCCESS
        } else SearchVerification.UNKNOWN
    }
}

object YouTubeSearchVerificationPolicy {
    fun verify(query: String, foregroundPackage: String?, visibleLabels: List<String>): SearchVerification {
        if (foregroundPackage != "com.google.android.youtube") return SearchVerification.UNKNOWN
        val visible = visibleLabels.joinToString(" ").lowercase(Locale.ROOT)
        val tokens = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= 2 }
        return if (tokens.isNotEmpty() && tokens.any(visible::contains)) SearchVerification.SUCCESS
        else SearchVerification.UNKNOWN
    }
}

/** General browser capability. It opens a standards-based search URL; observation and result
 * interpretation remain separate plan steps owned by UnifiedLyraAgent. */
class BrowserSearchTool(private val context: Context) {
    fun execute(request: BrowserSearchRequest, resolution: SearchResolution): BrowserSearchDispatch {
        val encoded = URLEncoder.encode(request.query, Charsets.UTF_8.name())
        val uri = Uri.parse("https://www.google.com/search?q=$encoded")
        val chrome = "com.android.chrome"
        val expected = resolution.targetPackage ?: chrome.takeIf {
            context.packageManager.getLaunchIntentForPackage(it) != null
        }
        val intent = if (resolution.selectedExecutor == BrowserSearchExecutor.CURRENT_GOOGLE_APP) {
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, request.query)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (expected != null) intent.setPackage(expected)
        return try {
            VoicePipelineLogger.debug(
                "SEARCH_EXECUTOR_ENTRY class=BrowserSearchTool method=execute turnId=service_owned " +
                    "finalTranscript=authorized query=${request.query.take(120)} destination=${resolution.destination} " +
                    "foregroundPackage=${resolution.targetPackage}"
            )
            VoicePipelineLogger.debug(
                "SEARCH_ACTION_STARTED executorClass=BrowserSearchTool intentAction=${intent.action} " +
                    "selectedExecutor=${resolution.selectedExecutor} destination=${resolution.destination} expectedPackage=$expected"
            )
            context.startActivity(intent)
            VoicePipelineLogger.debug(
                "SEARCH_ACTION_RETURNED executorClass=BrowserSearchTool accepted=true expectedPackage=$expected"
            )
            BrowserSearchDispatch(true, expected, "dispatched")
        } catch (error: Exception) {
            VoicePipelineLogger.debug(
                "SEARCH_ACTION_RETURNED executorClass=BrowserSearchTool accepted=false " +
                    "failure=${error.javaClass.simpleName} expectedPackage=$expected"
            )
            BrowserSearchDispatch(false, expected, "browser_unavailable")
        }
    }
}
