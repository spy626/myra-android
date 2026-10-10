package com.myra.assistant.screen

import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRole
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class RenderedBrowserPublicDestinationTest {
    private val browser = "com.android.chrome"

    private fun element(
        label: String,
        role: SemanticRole = SemanticRole.TEXT,
        actionable: Boolean = false,
        index: Int = 0,
    ) = SemanticElement(
        id = "4:8:$index", role = role, label = label,
        left = 10, top = 20 + index * 40, right = 900, bottom = 55 + index * 40,
        actionable = actionable,
    )

    private fun context(
        at: Long = 2_240L,
        elements: List<SemanticElement>,
        pkg: String = browser,
        window: Int = 4,
        generation: Long = 8L,
    ) = CurrentActivityContext(
        packageName = pkg, screenType = "WEB_PAGE", windowId = window,
        generation = generation, visibleElements = elements,
        confidence = .92, timestamp = at,
    )

    private fun foreground(
        pkg: String = browser,
        window: Int = 4,
        generation: Long = 8L,
    ) = ForegroundAppContext(
        packageName = pkg, windowId = window, generation = generation,
        observedAt = 2_240L,
    )

    private fun page(at: Long = 2_240L) = RenderedBrowserPageEvidence.Receipt(
        action = RenderedBrowserPageEvidence.SourceAction.EXPLICIT_LINK_TAP,
        browserPackage = browser, windowId = 4, generation = 8L,
        firstObservedAt = 1_800L, secondObservedAt = at,
        stableVisibleLines = listOf(
            "Version three introduces stability improvements for public readers"
        ),
        contentSha256 = "a".repeat(64),
    )

    private fun publicAddress(host: String = "example.com") =
        InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))

    @Test fun uniqueBrowserChromeHttpsUrlCanBindToSameVerifiedPageEvidence() {
        val observed = context(elements = listOf(
            element("Version three introduces stability improvements for public readers"),
            element(
                "https://example.com/releases com.android.chrome:id/url_bar",
                SemanticRole.TEXT_INPUT, true, 1
            ),
        ))
        val candidate = requireNotNull(RenderedBrowserPublicDestination.candidate(
            observed, foreground(), page(), 2_300L))
        assertEquals("https://example.com/releases", candidate.canonicalUrl)
        assertEquals("example.com", candidate.host)

        val destination = requireNotNull(RenderedBrowserPublicDestination.verifyPublic(
            candidate) { listOf(publicAddress()) })
        assertTrue(destination.publicDnsVerified)
        assertEquals(64, destination.urlSha256.length)
        assertFalse(destination.permitsNextAction)

        val bound = requireNotNull(RenderedBrowserPublicDestination.bind(page(), destination))
        assertTrue(bound.destinationUrlVerified)
        assertEquals("https://example.com/releases",
            bound.publicDestination?.canonicalUrl)
        assertFalse(bound.permitsNextAction)
    }

    @Test fun queryCredentialsHttpPrivatePathsAndAmbiguousBarsNeverBecomeDestinationEvidence() {
        fun candidateFor(label: String) = RenderedBrowserPublicDestination.candidate(
            context(elements = listOf(
                element("A sufficiently long stable public paragraph is visible here"),
                element(label, SemanticRole.TEXT_INPUT, true, 1),
            )),
            foreground(), page(), 2_300L
        )
        assertNull(candidateFor(
            "https://example.com/search?q=private com.android.chrome:id/url_bar"))
        assertNull(candidateFor(
            "https://example.com/?token=secret com.android.chrome:id/url_bar"))
        assertNull(candidateFor(
            "http://example.com/releases com.android.chrome:id/url_bar"))
        assertNull(candidateFor(
            "https://example.com/login com.android.chrome:id/url_bar"))
        assertNull(RenderedBrowserPublicDestination.candidate(
            context(elements = listOf(
                element("https://example.com/a com.android.chrome:id/url_bar",
                    SemanticRole.TEXT_INPUT, true, 1),
                element("https://example.org/b com.android.chrome:id/url_bar",
                    SemanticRole.TEXT_INPUT, true, 2),
            )),
            foreground(), page(), 2_300L
        ))
    }

    @Test fun pageInputsWithoutBrowserChromeIdentityAndPrivateScreensAreRejected() {
        assertNull(RenderedBrowserPublicDestination.candidate(
            context(elements = listOf(
                element("https://example.com/releases", SemanticRole.TEXT_INPUT, true, 1),
            )),
            foreground(), page(), 2_300L
        ))
        assertNull(RenderedBrowserPublicDestination.candidate(
            context(elements = listOf(
                element("Enter verification code"),
                element("https://example.com/releases com.android.chrome:id/url_bar",
                    SemanticRole.TEXT_INPUT, true, 1),
            )),
            foreground(), page(), 2_300L
        ))
        assertNull(RenderedBrowserPublicDestination.candidate(
            context(at = 2_241L, elements = listOf(
                element("https://example.com/releases com.android.chrome:id/url_bar",
                    SemanticRole.TEXT_INPUT, true, 1),
            )),
            foreground(), page(), 2_300L
        ))
    }

    @Test fun dnsMustResolveOnlyToPublicAddressesAndBindingMustMatchExactScreenIdentity() {
        val observed = context(elements = listOf(
            element("A sufficiently long stable public paragraph is visible here"),
            element("https://example.com/releases com.android.chrome:id/url_bar",
                SemanticRole.TEXT_INPUT, true, 1),
        ))
        val candidate = requireNotNull(RenderedBrowserPublicDestination.candidate(
            observed, foreground(), page(), 2_300L))
        assertNull(RenderedBrowserPublicDestination.verifyPublic(candidate) {
            listOf(InetAddress.getByAddress("example.com",
                byteArrayOf(10, 0, 0, 1)))
        })
        val verified = requireNotNull(RenderedBrowserPublicDestination.verifyPublic(
            candidate) { listOf(publicAddress()) })
        assertNull(RenderedBrowserPublicDestination.bind(
            page().copy(windowId = 99), verified))
        assertNull(RenderedBrowserPublicDestination.bind(
            page().copy(generation = 9L), verified))
    }
}
