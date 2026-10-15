package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRole
import java.util.Locale

/**
 * One explicitly named, visible browser link tap. User-authored words are authority;
 * model suggestions and page text never authorize actions. No URLs, hidden DOM, forms,
 * coordinates, cross-app navigation, retries, or automatic next links.
 */
internal object RenderedBrowserNavigationPolicy {
    data class Plan(
        val label: String,
        val packageName: String,
        val windowId: Int,
        val generation: Long,
        val originalText: Set<String>,
    )
    enum class Verification { BROWSER_CONTENT_CHANGED_URL_UNVERIFIED, UNKNOWN }

    private val direct = Regex(
        """(?iu)^(?:please\s+)?(?:lyra\s+)?(?:tap|click|open|press|kholo|dabao)\s+(?:the\s+|this\s+|that\s+|visible\s+)?(.{4,90}?)\s+(?:link|hyperlink)(?:\s+(?:please|karo))?$"""
    )
    private val objectFirst = Regex(
        """(?iu)^(.{4,90}?)\s+(?:link|hyperlink)\s+(?:kholo|dabao|click\s+karo|open\s+karo)$"""
    )
    private val sensitiveLabel = Regex(
        """(?iu)\b(?:login|log\s*in|sign\s*in|signup|register|logout|subscribe|unsubscribe|download|install|payment|checkout|purchase|buy|order|cart|delete|remove|account|settings|password|otp|pin|cvv|token|secret|authorize|allow|permission|grant|upload|submit|send|post|edit|save|confirm)\b"""
    )
    private val privateSurface = Regex(
        """(?iu)\b(?:password|passcode|otp|verification\s+code|checkout|payment\s+details|credit\s+card|debit\s+card|private\s+message|email\s+inbox|account\s+settings|medical\s+record)\b"""
    )
    private val urlOrEmail = Regex(
        """(?iu)(?:https?://|www\.|[\w.+%-]+@[\w.-]+\.[a-z]{2,})"""
    )
    private fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{M}\p{N}]+"""), " ")
        .trim().replace(Regex("""\s+"""), " ")

    /** Route even unsafe/ambiguous link-shaped requests here, so legacy click cannot bypass rejection. */
    fun isLinkShapedCommand(finalText: String, actualPackage: String?): Boolean {
        if (!RenderedBrowserObservation.isSupportedBrowser(actualPackage)) return false
        val normalized = finalText.trim()
        return Regex("""(?iu)^(?:please\s+)?(?:lyra\s+)?(?:tap|click|open|press|kholo|dabao)\b.*\b(?:link|hyperlink)\b""")
            .containsMatchIn(normalized) ||
            Regex("""(?iu)\b(?:link|hyperlink)\s+(?:kholo|dabao|click\s+karo|open\s+karo)\b""")
                .containsMatchIn(normalized)
    }

    private fun namedTarget(finalText: String): String? {
        val raw = finalText.trim().replace(Regex("""\s+"""), " ")
        if (raw.length !in 12..160 || urlOrEmail.containsMatchIn(raw) ||
            Regex("""(?iu)\b(?:don't|dont|do not|not|never|mat|nahi|without|instead|if|would|should|chahiye|or|either)\b""")
                .containsMatchIn(raw)
        ) return null
        val target = (direct.matchEntire(raw) ?: objectFirst.matchEntire(raw))
            ?.groupValues?.get(1)?.trim().orEmpty()
        val label = normalize(target)
        if (label.length !in 4..70 || label.split(' ').size > 8 ||
            sensitiveLabel.containsMatchIn(label) ||
            label in setOf("this", "that", "first", "second", "next", "the link",
                "a link", "any link", "visible", "here", "there", "this one")
        ) return null
        return label
    }

    private fun safeLine(element: SemanticElement): String? {
        val text = element.label.trim()
        if (text.length !in 4..180 || element.role == SemanticRole.TEXT_INPUT ||
            element.role == SemanticRole.UNKNOWN ||
            text.any(Char::isISOControl) || urlOrEmail.containsMatchIn(text) ||
            privateSurface.containsMatchIn(text) ||
            ScreenPrivacyPolicy.sensitiveCategory(text) != null
        ) return null
        return normalize(text).takeIf { it.length >= 4 }
    }

    fun plan(
        finalText: String,
        observed: CurrentActivityContext?,
        foreground: ForegroundAppContext?,
        now: Long,
    ): Plan? {
        val label = namedTarget(finalText) ?: return null
        if (foreground == null || !RenderedBrowserObservation.isSupportedBrowser(foreground.packageName) ||
            !foreground.rootAvailable || observed == null ||
            observed.packageName != foreground.packageName ||
            observed.windowId != foreground.windowId ||
            observed.generation != foreground.generation ||
            observed.timestamp <= 0 || observed.timestamp > now ||
            now - observed.timestamp > 850L || observed.confidence < .60 ||
            observed.visibleElements.any {
                privateSurface.containsMatchIn(it.label) ||
                    ScreenPrivacyPolicy.sensitiveCategory(it.label) != null
            }
        ) return null
        val matches = observed.visibleElements.take(120).filter {
            it.actionable && it.role in setOf(SemanticRole.BUTTON, SemanticRole.LINK, SemanticRole.NAVIGATION) &&
                safeLine(it) == label && !sensitiveLabel.containsMatchIn(it.label)
        }
        if (matches.size != 1) return null
        val initial = observed.visibleElements.mapNotNull(::safeLine).toSet()
        return Plan(matches.single().label.trim(), foreground.packageName,
            foreground.windowId, foreground.generation, initial)
    }

    fun allowsResolvedTarget(plan: Plan, candidateLabel: String, candidateRole: String,
        confidence: Double, width: Int, height: Int, screenWidth: Int, screenHeight: Int): Boolean =
        normalize(candidateLabel) == normalize(plan.label) &&
            !sensitiveLabel.containsMatchIn(candidateLabel) &&
            candidateRole in setOf("interactive", "button") &&
            confidence >= .90 && width > 0 && height > 0 &&
            width < screenWidth * .90 && height < screenHeight * .80

    /**
     * A single changed snapshot is only a provisional observation. The final result
     * needs two independent, time-separated accessibility reads with at least one
     * meaningful, NEW, non-actionable text line stable in BOTH.
     *
     * Both reads must come from the same post-action browser/window/generation.
     * This is not URL, DOM, HTTP-response or successful goal verification.
     */
    fun verifyStable(
        plan: Plan,
        first: CurrentActivityContext?,
        firstForeground: ForegroundAppContext?,
        second: CurrentActivityContext?,
        secondForeground: ForegroundAppContext?,
        dispatchedAt: Long,
        now: Long,
    ): Verification {
        if (first == null || second == null || firstForeground == null ||
            secondForeground == null || first.timestamp <= dispatchedAt ||
            second.timestamp - first.timestamp < 180L ||
            second.timestamp <= first.timestamp ||
            second.timestamp > now ||
            second.timestamp - dispatchedAt > 3_500L ||
            first.packageName != second.packageName ||
            first.windowId != second.windowId ||
            first.generation != second.generation ||
            firstForeground.packageName != secondForeground.packageName ||
            firstForeground.windowId != secondForeground.windowId ||
            firstForeground.generation != secondForeground.generation ||
            verify(plan, first, firstForeground, dispatchedAt, now) !=
                Verification.BROWSER_CONTENT_CHANGED_URL_UNVERIFIED ||
            verify(plan, second, secondForeground, dispatchedAt, now) !=
                Verification.BROWSER_CONTENT_CHANGED_URL_UNVERIFIED
        ) return Verification.UNKNOWN
        val initial = stableNovelLines(plan, first)
        val final = stableNovelLines(plan, second)
        return if (initial.any { it in final }) Verification.BROWSER_CONTENT_CHANGED_URL_UNVERIFIED
        else Verification.UNKNOWN
    }

    private val transientPageText = Regex(
        """(?iu)\b(?:loading|please\s*wait|redirecting|working|retry|refresh|failed\s*to\s*load|site\s*can.?t\s*be\s*reached|connection\s*error|connection\s*lost|cookie\s*consent|accept\s*cookies|advertisement|sponsored|notification|permission)\b"""
    )

    private fun stableNovelLines(plan: Plan, observed: CurrentActivityContext): Set<String> =
        observed.visibleElements.asSequence().take(120)
            .filter { it.role == SemanticRole.TEXT && !it.actionable }
            .mapNotNull(::safeLine)
            .filter {
                it.length >= 20 && it !in plan.originalText &&
                    it != normalize(plan.label) && !transientPageText.containsMatchIn(it)
            }
            .distinct().take(24).toSet()

    /** A content change proves only a changed observed browser screen, never the destination URL. */
    fun verify(
        plan: Plan, after: CurrentActivityContext?,
        foreground: ForegroundAppContext?, dispatchedAt: Long, now: Long,
    ): Verification {
        if (dispatchedAt <= 0L || after == null || foreground == null ||
            foreground.packageName != plan.packageName || !foreground.rootAvailable ||
            after.packageName != foreground.packageName ||
            after.windowId != foreground.windowId || after.generation != foreground.generation ||
            after.timestamp <= dispatchedAt || after.timestamp > now ||
            now - after.timestamp > 1_500L || after.confidence < .60 ||
            after.visibleElements.any {
                privateSurface.containsMatchIn(it.label) ||
                    ScreenPrivacyPolicy.sensitiveCategory(it.label) != null
            }
        ) return Verification.UNKNOWN
        val newContent = after.visibleElements.asSequence()
            .filter { it.role in setOf(SemanticRole.TEXT, SemanticRole.BUTTON, SemanticRole.LINK) }
            .mapNotNull(::safeLine).filter { it.length >= 12 &&
                it !in plan.originalText && it != normalize(plan.label) &&
                it !in setOf("loading please wait", "loading content", "please wait")
            }.distinct().take(2).toList()
        return if (newContent.isNotEmpty()) Verification.BROWSER_CONTENT_CHANGED_URL_UNVERIFIED
        else Verification.UNKNOWN
    }
}
