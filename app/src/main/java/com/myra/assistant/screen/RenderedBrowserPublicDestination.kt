package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticRole
import com.myra.assistant.ui.workspace.WorkspaceAgentReachPolicy
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Ephemeral public destination identity for one already verified named-link navigation.
 *
 * The raw address-bar value is inspected locally only. A receipt is emitted only when one
 * browser-chrome HTTPS URL is structurally safe, query-free, tied to the same verified
 * browser/window/generation, and all DNS answers are public. This does NOT prove that the
 * rendered text came from an HTTP response at this URL and never grants another action.
 */
internal object RenderedBrowserPublicDestination {
    data class Candidate(
        val browserPackage: String,
        val windowId: Int,
        val generation: Long,
        val observedAt: Long,
        val canonicalUrl: String,
        val host: String,
    )

    data class Receipt(
        val browserPackage: String,
        val windowId: Int,
        val generation: Long,
        val observedAt: Long,
        val canonicalUrl: String,
        val host: String,
        val urlSha256: String,
        val publicDnsVerified: Boolean = true,
        val permitsNextAction: Boolean = false,
    )

    private val browserChromeSignal = Regex(
        """(?iu)(?:\b(?:url|address|location)[ _-]?bar\b|\b(?:omnibox|omnibar)\b|""" +
            """search or (?:type|enter)(?: a)? (?:web )?address|""" +
            """[:/._-](?:url_bar|address_bar|location_bar|omnibox|omnibar)(?:\b|[:/._-]))"""
    )
    private val explicitHttps = Regex("""(?i)https://[^\s<>"'|]+""")
    private val privateSurface = Regex(
        """(?iu)\b(?:password|passcode|otp|verification\s+code|checkout|payment\s+details|""" +
            """credit\s+card|debit\s+card|private\s+message|email\s+inbox|account\s+settings|""" +
            """medical\s+record|two[ -]?factor|2fa)\b"""
    )
    private val privatePath = Regex(
        """(?iu)(?:^|/)(?:login|log-in|signin|sign-in|oauth|authorize|callback|checkout|payment|""" +
            """account/settings)(?:/|$)"""
    )

    private fun extractHttps(label: String): String? =
        explicitHttps.find(label)?.value?.trimEnd('.', ',', ';', ')', ']', '}')

    fun candidate(
        observed: CurrentActivityContext?,
        foreground: ForegroundAppContext?,
        page: RenderedBrowserPageEvidence.Receipt,
        now: Long,
    ): Candidate? {
        if (page.action != RenderedBrowserPageEvidence.SourceAction.EXPLICIT_LINK_TAP ||
            observed == null || foreground == null ||
            !RenderedBrowserObservation.isSupportedBrowser(observed.packageName) ||
            observed.packageName != foreground.packageName ||
            observed.windowId != foreground.windowId ||
            observed.generation != foreground.generation ||
            observed.packageName != page.browserPackage ||
            observed.windowId != page.windowId ||
            observed.generation != page.generation ||
            observed.timestamp != page.secondObservedAt ||
            observed.timestamp <= 0L || observed.timestamp > now ||
            now - observed.timestamp > 1_500L || observed.confidence < .60 ||
            !foreground.rootAvailable ||
            observed.visibleElements.take(120).any {
                privateSurface.containsMatchIn(it.label) ||
                    ScreenPrivacyPolicy.sensitiveCategory(it.label) != null
            }
        ) return null

        val targets = observed.visibleElements.asSequence().take(120)
            .filter { it.role in setOf(SemanticRole.TEXT_INPUT, SemanticRole.SEARCH) }
            .filter { browserChromeSignal.containsMatchIn(it.label) }
            .mapNotNull { extractHttps(it.label) }
            .mapNotNull { raw ->
                runCatching { WorkspaceAgentReachPolicy.parse(raw) }.getOrNull()
            }
            // Keep this first slice privacy-minimal: search/session query strings are not
            // promoted into destination receipts even when the public URL policy accepts them.
            .filter { target ->
                val uri = runCatching { java.net.URI(target.canonicalUrl) }.getOrNull()
                uri != null && uri.rawQuery.isNullOrBlank() &&
                    !privatePath.containsMatchIn(uri.path.orEmpty())
            }
            .distinctBy { it.canonicalUrl }
            .toList()

        if (targets.size != 1) return null
        val target = targets.single()
        return Candidate(
            browserPackage = observed.packageName,
            windowId = observed.windowId,
            generation = observed.generation,
            observedAt = observed.timestamp,
            canonicalUrl = target.canonicalUrl,
            host = target.host,
        )
    }

    fun verifyPublic(
        candidate: Candidate,
        resolve: (String) -> List<InetAddress> = { host ->
            InetAddress.getAllByName(host).toList()
        },
    ): Receipt? {
        val target = runCatching {
            WorkspaceAgentReachPolicy.parse(candidate.canonicalUrl)
        }.getOrNull() ?: return null
        val uri = runCatching { java.net.URI(target.canonicalUrl) }.getOrNull() ?: return null
        if (target.host != candidate.host || target.canonicalUrl != candidate.canonicalUrl ||
            !uri.rawQuery.isNullOrBlank() || privatePath.containsMatchIn(uri.path.orEmpty())
        ) return null
        val addresses = runCatching { resolve(target.host) }.getOrNull() ?: return null
        if (runCatching {
                WorkspaceAgentReachPolicy.validateResolvedAddresses(target.host, addresses)
            }.isFailure
        ) return null

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(target.canonicalUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Receipt(
            browserPackage = candidate.browserPackage,
            windowId = candidate.windowId,
            generation = candidate.generation,
            observedAt = candidate.observedAt,
            canonicalUrl = target.canonicalUrl,
            host = target.host,
            urlSha256 = hash,
        )
    }

    fun bind(
        page: RenderedBrowserPageEvidence.Receipt,
        destination: Receipt,
    ): RenderedBrowserPageEvidence.Receipt? {
        if (page.action != RenderedBrowserPageEvidence.SourceAction.EXPLICIT_LINK_TAP ||
            page.browserPackage != destination.browserPackage ||
            page.windowId != destination.windowId ||
            page.generation != destination.generation ||
            page.secondObservedAt != destination.observedAt ||
            !destination.publicDnsVerified || destination.permitsNextAction
        ) return null
        return page.copy(
            destinationUrlVerified = true,
            publicDestination = destination,
            permitsNextAction = false,
        )
    }
}
