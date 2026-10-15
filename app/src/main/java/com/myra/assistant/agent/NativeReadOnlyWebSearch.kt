package com.myra.assistant.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent

/**
 * Separate free, read-only Android search-app executor. No credentials, cookies,
 * API keys, hidden WebView or scripted webpage operations are accessed.
 */
object NativeReadOnlyWebSearchPolicy {
    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    private val protectedInput = Regex(
        """(?i)\b(?:password|passcode|otp|pin|cvv|api[ -]?key|access[ -]?token|private[ -]?key|secret|bank account|card number)\b"""
    )

    fun safeQuery(query: String): Boolean =
        query.trim().length in 2..140 && query.none(Char::isISOControl) &&
            !protectedInput.containsMatchIn(query)

    fun eligible(
        request: BrowserSearchRequest,
        resolution: SearchResolution,
        foregroundPackage: String?,
        executorAvailable: Boolean,
    ): Boolean = executorAvailable &&
        safeQuery(request.query) &&
        request.explicitDestination == null &&
        resolution.destination == SearchDestination.BROWSER &&
        resolution.selectedExecutor == BrowserSearchExecutor.CURRENT_GOOGLE_APP &&
        resolution.targetPackage == GOOGLE_PACKAGE &&
        foregroundPackage == GOOGLE_PACKAGE
}

class NativeReadOnlyWebSearchTool(private val context: Context) {
    private fun searchIntent(query: String) =
        Intent(Intent.ACTION_WEB_SEARCH)
            .setPackage(NativeReadOnlyWebSearchPolicy.GOOGLE_PACKAGE)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun isAvailable(): Boolean = context.packageManager.resolveActivity(
        searchIntent("search"), 0
    )?.activityInfo?.packageName == NativeReadOnlyWebSearchPolicy.GOOGLE_PACKAGE

    fun execute(query: String, eligible: Boolean): GeneralActionResult {
        if (!eligible || !NativeReadOnlyWebSearchPolicy.safeQuery(query)) {
            return GeneralActionResult(false, failureReason = "native_web_search_not_authorized")
        }
        if (!isAvailable()) return GeneralActionResult(false, failureReason = "native_web_search_unavailable")
        return try {
            context.startActivity(searchIntent(query))
            // Dispatch acceptance is never search-result verification; GeneralVerifier
            // must still observe a fresh, matching Google-app results screen.
            GeneralActionResult(true, metadata = mapOf("route" to "native_google_web_search"))
        } catch (_: Exception) {
            GeneralActionResult(false, failureReason = "native_web_search_dispatch_rejected")
        }
    }
}
