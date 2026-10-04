package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRole
import java.security.MessageDigest
import java.util.Locale

/**
 * Ephemeral receipt for genuinely visible, stable public text across two separately
 * observed Accessibility screen samples. NOT a URL, HTTP, DOM or destination receipt.
 * No automatic persistence, AI-provider sharing or permission/action authority.
 */
internal object RenderedBrowserPageEvidence {
    enum class SourceAction { EXPLICIT_LINK_TAP, EXPLICIT_ONE_SCROLL }

    data class Receipt(
        val action: SourceAction,
        val browserPackage: String,
        val windowId: Int,
        val generation: Long,
        val firstObservedAt: Long,
        val secondObservedAt: Long,
        val stableVisibleLines: List<String>,
        val contentSha256: String,
        val destinationUrlVerified: Boolean = false,
        val permitsNextAction: Boolean = false,
    ) {
        /** Human-readable preview is a quotation of untrusted screen data, not a direction. */
        fun localPreview(): String = stableVisibleLines.first().take(130)
    }

    private val transient = Regex(
        """(?iu)\b(?:loading|please\s*wait|redirecting|retry|refresh|site\s*can.?t\s*be\s*reached|connection\s*error|cookie\s*consent|accept\s*cookies|advertisement|sponsored|notification|permission)\b"""
    )
    private val privateContent = Regex(
        """(?iu)\b(?:password|passcode|otp|verification\s*code|checkout|payment\s*details|credit\s*card|debit\s*card|private\s*message|email\s*inbox|account\s*settings|medical\s*record|access\s*token|api\s*key|seed\s*phrase)\b"""
    )
    private val urlOrIdentifier = Regex(
        """(?iu)(?:https?://|www\.|[\w.+%-]+@[\w.-]+\.[a-z]{2,}|\b(?:token|secret|password|session|key)\s*[=:]\s*\S+)"""
    )
    private fun key(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{M}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    private fun safeLine(element: SemanticElement): String? {
        val raw = element.label.trim().replace(Regex("""\s+"""), " ")
        if (element.role != SemanticRole.TEXT || element.actionable ||
            raw.length !in 20..180 || raw.any(Char::isISOControl) ||
            privateContent.containsMatchIn(raw) || transient.containsMatchIn(raw) ||
            urlOrIdentifier.containsMatchIn(raw) ||
            ScreenPrivacyPolicy.sensitiveCategory(raw) != null
        ) return null
        return raw.takeIf { key(it).length >= 20 }
    }

    private fun capture(
        action: SourceAction,
        first: CurrentActivityContext,
        second: CurrentActivityContext,
        excludedBefore: Set<String>,
    ): Receipt? {
        if (first.packageName != second.packageName ||
            first.windowId != second.windowId || first.generation != second.generation ||
            !RenderedBrowserObservation.isSupportedBrowser(second.packageName) ||
            second.timestamp <= first.timestamp || first.timestamp <= 0L
        ) return null
        val excluded = excludedBefore.map(::key).toSet()
        val initial = first.visibleElements.take(120).mapNotNull(::safeLine)
            .associateBy(::key)
        val stable = second.visibleElements.take(120).mapNotNull(::safeLine)
            .distinctBy(::key).filter { line ->
                val normalized = key(line)
                normalized !in excluded && normalized in initial
            }.take(3)
        if (stable.isEmpty()) return null
        val contentHash = MessageDigest.getInstance("SHA-256")
            .digest(stable.joinToString("\n", transform = ::key).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Receipt(action, second.packageName, second.windowId, second.generation,
            first.timestamp, second.timestamp, stable, contentHash)
    }

    fun afterNamedLink(
        plan: RenderedBrowserNavigationPolicy.Plan,
        first: CurrentActivityContext?,
        firstForeground: ForegroundAppContext?,
        second: CurrentActivityContext?,
        secondForeground: ForegroundAppContext?,
        dispatchedAt: Long,
        now: Long,
    ): Receipt? {
        if (RenderedBrowserNavigationPolicy.verifyStable(
                plan, first, firstForeground, second, secondForeground, dispatchedAt, now) !=
            RenderedBrowserNavigationPolicy.Verification.BROWSER_CONTENT_CHANGED_URL_UNVERIFIED
        ) return null
        return capture(SourceAction.EXPLICIT_LINK_TAP, first!!, second!!, plan.originalText)
    }

    fun afterOneScroll(
        plan: RenderedBrowserScrollPolicy.Plan,
        first: CurrentActivityContext?,
        firstForeground: ForegroundAppContext?,
        second: CurrentActivityContext?,
        secondForeground: ForegroundAppContext?,
        dispatchedAt: Long,
        now: Long,
    ): Receipt? {
        if (RenderedBrowserScrollPolicy.verify(
                plan, first, firstForeground, second, secondForeground, dispatchedAt, now) !=
            RenderedBrowserScrollPolicy.Verification.NEW_STABLE_VISIBLE_TEXT_URL_UNVERIFIED
        ) return null
        return capture(SourceAction.EXPLICIT_ONE_SCROLL, first!!, second!!, plan.beforeText)
    }
}
