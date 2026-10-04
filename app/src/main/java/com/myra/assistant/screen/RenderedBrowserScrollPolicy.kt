package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRole
import java.util.Locale

/**
 * Exactly one final-user-authorized foreground browser scroll. The existing Accessibility
 * owner performs the action; this policy only supplies a bounded observe/verify contract.
 * No automatic repetition, hidden DOM, URL claim, paid browser, or coordinate fallback.
 */
internal object RenderedBrowserScrollPolicy {
    data class Plan(
        val down: Boolean,
        val packageName: String,
        val windowId: Int,
        val generation: Long,
        val beforeText: Set<String>,
    )
    enum class Verification { NEW_STABLE_VISIBLE_TEXT_URL_UNVERIFIED, UNKNOWN }

    private val shape = Regex(
        """(?iu)^(?:please\s+)?(?:lyra\s+)?scroll\b.*\b(?:browser|web\s*page|webpage|website)\b"""
    )
    private val command = Regex(
        """(?iu)^(?:please\s+)?(?:lyra\s+)?scroll\s+(?:(?:this|the|current)\s+)?(?:browser\s+page|web\s*page|webpage|website(?:\s+page)?)\s+(down|up)(?:\s+once)?$"""
    )
    private val sensitiveScreen = Regex(
        """(?iu)\b(?:password|passcode|otp|verification\s+code|checkout|payment\s+details|credit\s+card|debit\s+card|private\s+message|email\s+inbox|account\s+settings|medical\s+record)\b"""
    )
    private val riskyText = Regex(
        """(?iu)(?:https?://|www\.|[\w.+%-]+@[\w.-]+\.[a-z]{2,}|\b(?:token|secret|password|session|api\s*key)\s*[=:]\s*\S+)"""
    )
    private val transientText = Regex(
        """(?iu)\b(?:loading|please\s*wait|redirecting|retry|refresh|failed\s*to\s*load|connection\s*error|cookie\s*consent|advertisement|sponsored|notification|permission)\b"""
    )
    private fun normalize(s: String) = s.lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{M}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ").trim()
    private fun sensitive(context: CurrentActivityContext): Boolean =
        context.visibleElements.any {
            sensitiveScreen.containsMatchIn(it.label) ||
                ScreenPrivacyPolicy.sensitiveCategory(it.label) != null
        }
    private fun readable(element: SemanticElement): String? {
        val label = element.label.trim()
        if (element.actionable || element.role != SemanticRole.TEXT || label.length !in 20..180 ||
            riskyText.containsMatchIn(label) || transientText.containsMatchIn(label) ||
            sensitiveScreen.containsMatchIn(label) ||
            ScreenPrivacyPolicy.sensitiveCategory(label) != null
        ) return null
        return normalize(label).takeIf { it.length >= 20 }
    }
    fun isBrowserPageScrollShaped(finalText: String, packageName: String?): Boolean =
        RenderedBrowserObservation.isSupportedBrowser(packageName) &&
            shape.containsMatchIn(finalText.trim())

    fun plan(
        finalText: String,
        observed: CurrentActivityContext?,
        foreground: ForegroundAppContext?,
        now: Long,
    ): Plan? {
        val direction = command.matchEntire(finalText.trim().replace(Regex("""\s+"""), " "))
            ?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return null
        if (foreground == null || !RenderedBrowserObservation.isSupportedBrowser(foreground.packageName) ||
            !foreground.rootAvailable || observed == null ||
            observed.packageName != foreground.packageName ||
            observed.windowId != foreground.windowId ||
            observed.generation != foreground.generation ||
            observed.timestamp <= 0L || observed.timestamp > now ||
            now - observed.timestamp > 850L || observed.confidence < .60 ||
            sensitive(observed)
        ) return null
        val beforeText = observed.visibleElements.take(120).mapNotNull(::readable).toSet()
        if (beforeText.isEmpty()) return null
        return Plan(direction == "down", foreground.packageName,
            foreground.windowId, foreground.generation, beforeText)
    }

    /** Two independent observations must contain the same NEW non-actionable public text. */
    fun verify(
        plan: Plan,
        first: CurrentActivityContext?,
        firstForeground: ForegroundAppContext?,
        second: CurrentActivityContext?,
        secondForeground: ForegroundAppContext?,
        dispatchedAt: Long,
        now: Long,
    ): Verification {
        if (first == null || second == null || firstForeground == null ||
            secondForeground == null || dispatchedAt <= 0L ||
            first.timestamp <= dispatchedAt || second.timestamp <= first.timestamp ||
            second.timestamp - first.timestamp < 180L || second.timestamp > now ||
            now - second.timestamp > 1_500L || second.timestamp - dispatchedAt >= 3_500L ||
            first.confidence < .60 || second.confidence < .60 ||
            listOf(first, second).any {
                it.packageName != plan.packageName || it.windowId != plan.windowId ||
                    it.generation != plan.generation || sensitive(it)
            } ||
            listOf(firstForeground, secondForeground).any {
                it.packageName != plan.packageName || it.windowId != plan.windowId ||
                    it.generation != plan.generation || !it.rootAvailable
            }
        ) return Verification.UNKNOWN
        val initial = first.visibleElements.take(120).mapNotNull(::readable)
            .filterNot { it in plan.beforeText }.toSet()
        val final = second.visibleElements.take(120).mapNotNull(::readable)
            .filterNot { it in plan.beforeText }.toSet()
        return if (initial.any { it in final }) Verification.NEW_STABLE_VISIBLE_TEXT_URL_UNVERIFIED
        else Verification.UNKNOWN
    }
}
