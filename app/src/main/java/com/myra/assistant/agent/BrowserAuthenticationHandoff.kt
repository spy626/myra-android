package com.myra.assistant.agent

import com.myra.assistant.screen.RenderedBrowserObservation
import com.myra.assistant.screen.ScreenPrivacyPolicy
import java.util.Locale

/**
 * Private browser authentication boundary for an already authorized research task.
 * Only structural UI flags are used; no form values, OTPs, screenshots, URLs, cookie,
 * credential or identity strings enter the pause record, model prompt or task history.
 * This neither performs login nor claims a verified signed-in session.
 */
object BrowserAuthenticationHandoff {
    const val MAX_WAIT_MS = 5L * 60_000L
    const val MIN_SAMPLE_GAP_MS = 250L
    data class Pause(
        val taskId: String,
        val turnId: Long,
        val browserPackage: String,
        val windowId: Int,
        val startedAt: Long,
        val deadlineAt: Long,
    )

    private val challenge = Regex(
        """(?iu)\b(?:sign[ -]?in|log[ -]?in|login|authentication required|verify (?:it.?s |your )?you|unlock your account|two[ -]?factor|2fa)\b"""
    )
    private val credential = Regex(
        """(?iu)\b(?:password|passcode|passkey|one[ -]?time password|otp|verification code|authenticator|continue with google|use another account)\b"""
    )
    private val stillPrivate = Regex(
        """(?iu)\b(?:sign[ -]?in|log[ -]?in|login|password|passcode|passkey|otp|verification code|authenticator|two[ -]?factor|2fa|payment|checkout|private message|email inbox|account settings|credit card)\b"""
    )
    private val identifiers = Regex(
        """(?iu)(?:https?://|www\.|[\w.%+-]+@[\w.-]+\.[a-z]{2,}|\b(?:token|session|secret|key)\s*[:=])"""
    )
    private fun fresh(c: CurrentActivityContext?, now: Long): Boolean =
        c != null && c.timestamp > 0L && c.timestamp <= now &&
            now - c.timestamp <= 1_500L && c.confidence >= .60 &&
            RenderedBrowserObservation.isSupportedBrowser(c.packageName)

    fun detect(task: GeneralRuntimeTask, c: CurrentActivityContext?, now: Long): Pause? {
        if (task.intent.turnIntent != TurnIntent.MULTI_STEP_GOAL ||
            task.status != AgentRuntimeStatus.WAITING_FOR_RESULT ||
            task.currentStep?.capability !in setOf(ToolCapability.BROWSER_SEARCH, ToolCapability.WEB_SEARCH) ||
            !fresh(c, now) || c == null ||
            task.intent.relevantApp != c.packageName
        ) return null
        // A heading and an interactive authentication control are both required.
        // Labels are examined locally as booleans; they are never copied to a record.
        val heading = c.visibleElements.take(100).any {
            it.role in setOf(SemanticRole.TEXT, SemanticRole.BUTTON) &&
                challenge.containsMatchIn(it.label)
        }
        val control = c.visibleElements.take(100).any {
            (it.role == SemanticRole.TEXT_INPUT ||
                it.actionable && it.role in setOf(SemanticRole.BUTTON, SemanticRole.LINK)) &&
                (credential.containsMatchIn(it.label) ||
                    it.role == SemanticRole.TEXT_INPUT && it.label.isBlank())
        }
        if (!heading || !control || now > Long.MAX_VALUE - MAX_WAIT_MS) return null
        return Pause(task.id, task.turnId, c.packageName, c.windowId, now, now + MAX_WAIT_MS)
    }

    private fun publicLines(c: CurrentActivityContext): Set<String>? {
        // Any auth/private indicator blocks the *entire* sample. No text-only bypass.
        if (c.visibleElements.take(100).any {
            stillPrivate.containsMatchIn(it.label) ||
                ScreenPrivacyPolicy.sensitiveCategory(it.label) != null
        }) return null
        return c.visibleElements.asSequence().take(100)
            .filter { it.role == SemanticRole.TEXT && !it.actionable }
            .map { it.label.trim().replace(Regex("""\s+"""), " ") }
            .filter {
                it.length in 24..160 && it.count(Char::isLetter) >= 20 &&
                    !it.any(Char::isISOControl) && !identifiers.containsMatchIn(it)
            }
            .map { it.lowercase(Locale.ROOT) }
            .distinct().take(12).toSet()
    }

    /** Only permits a fresh RE-VERIFICATION of the original action, not next navigation. */
    fun canReverify(pause: Pause, first: CurrentActivityContext?, second: CurrentActivityContext?,
        now: Long): Boolean {
        if (!fresh(first, now) || !fresh(second, now) ||
            first == null || second == null ||
            now > pause.deadlineAt ||
            first.packageName != pause.browserPackage || second.packageName != pause.browserPackage ||
            first.windowId != pause.windowId || second.windowId != pause.windowId ||
            first.generation != second.generation ||
            first.timestamp <= pause.startedAt ||
            second.timestamp - first.timestamp < MIN_SAMPLE_GAP_MS ||
            second.timestamp <= first.timestamp
        ) return false
        val a = publicLines(first) ?: return false
        val b = publicLines(second) ?: return false
        return (a intersect b).size >= 2
    }
}
