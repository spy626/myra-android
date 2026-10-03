package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

/** The September 21 phone recording used opacity: 0/1, not display: none/block.
 * This reproduces the visible saved source without copying private app data.
 */
class WorkspaceWebsiteOpacityFeedbackPhoneRegressionTest {
    private val goal = "Minicoy Island mobile website: blue hero Welcome to Minicoy, orange Explore Minicoy " +
        "button scrolls to Things to Explore and shows Exploring Minicoy!; Beaches, Lighthouse, " +
        "Local Food compact pink cards."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private val html = """<!DOCTYPE html><html lang="en"><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="style.css"></head><body>
<header class="hero"><h1>Welcome to Minicoy</h1><a href="#lyra-explore-section" class="btn">Explore Minicoy</a></header>
<section class="cards"><div class="lyra-requested-explore-cards"><article class="lyra-requested-explore-card"><h3>Beaches</h3><p>Explore coastal scenery.</p></article><article class="lyra-requested-explore-card"><h3>Lighthouse</h3><p>Learn about an island landmark.</p></article><article class="lyra-requested-explore-card"><h3>Local Food</h3><p>Discover island flavours.</p></article></div></section>
<section id="explore-section" class="explore"><h2 id="lyra-explore-section" class="lyra-explore-target">Things to Explore</h2><p class="lyra-explore-feedback" role="status" aria-live="polite">Exploring Minicoy!</p>
<p class="feedback">Exploring Minicoy!</p></section><script src="script.js"></script></body></html>""".trimIndent()
    private val css = """.hero { background: #087e93; }
.explore { padding: 2rem 1rem; text-align: center; }
.explore .feedback {
    margin-top: 1rem;
    font-size: 1.2rem;
    color: #fb923c;
    opacity: 0;
    transition: opacity 0.3s ease;
}
/* Show feedback when target */
#explore-section:target .feedback {
    opacity: 1;
}
/* LYRA Explore target feedback */
.lyra-explore-target:target { outline: 2px solid #0f766e; }
.lyra-explore-feedback { display: none; }
.lyra-explore-target:target + .lyra-explore-feedback { display: block; }
""".trimIndent()
    private fun source(page: String = html, styles: String = css, js: String = "") =
        mapOf("index.html" to page, "style.css" to styles, "script.js" to js)

    @Test fun realReviewRemovesOnlyDeadOpacityPairAndKeepsNativeClickFeedback() {
        val result = WorkspaceWebsiteVisualQuality.review(fresh, source()).files
        val page = result.getValue("index.html")
        val styles = result.getValue("style.css")
        assertEquals(1, Regex("Exploring Minicoy!").findAll(page).count())
        assertFalse(page.contains("class=\"feedback\""))
        assertFalse(styles.contains(".explore .feedback"))
        assertFalse(styles.contains("#explore-section:target .feedback"))
        assertTrue(page.contains("href=\"#lyra-explore-section\""))
        assertTrue(page.contains("class=\"lyra-explore-feedback\" role=\"status\""))
        assertTrue(styles.contains(".lyra-explore-target:target + .lyra-explore-feedback"))
        assertTrue(styles.contains(".hero { background: #087e93; }"))
        assertTrue(styles.contains(".explore { padding: 2rem 1rem; text-align: center; }"))
        assertTrue(page.contains("Explore coastal scenery."))
        assertEquals(result, WorkspaceWebsiteConsistency.verify(fresh, result))
        assertEquals(result, WorkspaceWebsiteVisualQuality.review(fresh, result).files)
    }

    @Test fun opacityCleanupFailsClosedForOtherConsumersAndUnverifiedPairs() {
        val cases = listOf(
            source(styles = css.replace("opacity: 0;", "opacity: 0.5;")),
            source(styles = css.replace("opacity: 1;", "opacity: 0.8;")),
            source(styles = css.replace("opacity: 1;", "display: block;")),
            source(styles = css.replace("opacity: 0;", "display: none;")),
            source(styles = css + "\n.footer .feedback { color: red; }"),
            source(styles = css.replace(".explore .feedback {", ".explore .feedback, .hint {")),
            source(styles = css.replace("#explore-section:target .feedback {",
                "#explore-section:target .feedback, .hint {")),
            source(page = html.replace("class=\"explore\"", "class=\"other\"")),
            source(page = html.replace("id=\"explore-section\"", "id=\"other\"")),
            source(page = html.replace("<p class=\"feedback\">", "<p class=\"feedback\" onclick=\"other()\">")),
            source(js = "document.querySelector('.feedback').textContent = 'Something else';")
        )
        cases.forEach { input ->
            assertEquals("No ambiguous or unrelated code may be dropped", input,
                WorkspaceWebsiteDuplicateFeedbackCleanup.review(fresh, input))
        }
        val established = fresh.copy(original = source().mapValues { it.value })
        assertEquals(source(), WorkspaceWebsiteDuplicateFeedbackCleanup.review(established, source()))
    }
}
