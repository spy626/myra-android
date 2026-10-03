package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteDuplicateFeedbackCleanupTest {
    private val goal = "Minicoy Island mobile website: blue hero Welcome to Minicoy, orange Explore Minicoy " +
        "button scrolls to Things to Explore and shows Exploring Minicoy!; Beaches, Lighthouse, " +
        "Local Food compact pink cards."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    // The actual structure visible in the September 21 phone recording: native target
    // is on the heading; the redundant paragraph targets its containing section instead.
    private val html = """<!doctype html><html><head><link rel="stylesheet" href="style.css"></head><body>
        <header><h1>Welcome to Minicoy</h1><a href="#lyra-explore-section">Explore Minicoy</a></header>
        <section class="cards"><h3>Beaches</h3><h3>Lighthouse</h3><h3>Local Food</h3></section>
        <section id="explore-section" class="explore"><h2 id="lyra-explore-section" class="lyra-explore-target">Things to Explore</h2><p class="lyra-explore-feedback" role="status" aria-live="polite">Exploring Minicoy!</p>
        <p class="feedback">Exploring Minicoy!</p><p>Discover the pristine beaches.</p></section>
        <script src="script.js"></script></body></html>""".trimIndent()
    private val oldStyles = """body { color: #123; }
        .feedback {display:none; font-weight:bold; color:#fb923c; margin-bottom:0.5rem;}
        /* Show feedback when section is target */
        #explore-section:target .feedback {display:block;}
        /* LYRA Explore target feedback */
        .lyra-explore-target:target { outline: 2px solid #0f766e; }
        .lyra-explore-feedback { display: none; }
        .lyra-explore-target:target + .lyra-explore-feedback { display:block; }
        """.trimIndent()
    private fun output(page: String = html, styles: String = oldStyles, script: String = "") =
        mapOf("index.html" to page, "style.css" to styles, "script.js" to script)

    @Test fun recordedDuplicateIsRemovedAndWorkingTargetPreservedInRealReviewPipeline() {
        val review = WorkspaceWebsiteVisualQuality.review(fresh, output())
        val cleaned = review.files
        val page = cleaned.getValue("index.html")
        val css = cleaned.getValue("style.css")
        assertEquals(1, Regex("Exploring Minicoy!").findAll(page).count())
        assertFalse(page.contains("class=\"feedback\""))
        assertFalse(css.contains("#explore-section:target .feedback"))
        assertFalse(Regex("""(?m)^\s*\.feedback\s*\{""").containsMatchIn(css))
        assertTrue(css.contains(".lyra-explore-target:target + .lyra-explore-feedback"))
        assertTrue(page.contains("href=\"#lyra-explore-section\""))
        assertTrue(page.contains("Discover the pristine beaches."))
        assertTrue(css.contains("body { color: #123; }"))
        assertEquals(cleaned, WorkspaceWebsiteVisualQuality.review(fresh, cleaned).files)
        assertEquals(cleaned, WorkspaceWebsiteConsistency.verify(fresh, cleaned))
    }

    @Test fun establishedSiteAndOtherGoalsAreByteForBytePreserved() {
        val source = output()
        val established = fresh.copy(original = source.mapValues { it.value })
        assertEquals(source, WorkspaceWebsiteDuplicateFeedbackCleanup.review(established, source))
        assertEquals(source, WorkspaceWebsiteDuplicateFeedbackCleanup.review(
            fresh.copy(goal = "Build a portfolio website"), source))
    }

    @Test fun unknownJsOtherConsumersAndUnverifiedFeedbackNeverGetDiscarded() {
        val variants = listOf(
            output(script = "document.querySelector('.feedback').textContent = 'Changed';"),
            output(page = html.replace("<p class=\"feedback\">", "<p class=\"feedback\" onclick=\"go()\">")),
            output(page = html.replace("<p>Discover the pristine beaches.</p>",
                "<p>Discover the pristine beaches.</p><div class=\"feedback\">Other use</div>")),
            output(page = html.replace("</p>\n        <p class=\"feedback\">",
                "</p><span>Separate action</span><p class=\"feedback\">")),
            output(page = html.replace("href=\"#lyra-explore-section\"", "href=\"#explore-section\"")),
            output(styles = oldStyles.replace(".feedback {display:none;", ".feedback, .hint {display:none;")),
            output(styles = oldStyles.replace(".lyra-explore-target:target + .lyra-explore-feedback",
                ".never-shows-feedback")),
            output(page = html.replace("<p class=\"feedback\">Exploring Minicoy!</p>",
                "<p class=\"feedback\">Different status</p>"))
        )
        variants.forEach { source ->
            assertEquals("Ambiguous or unrelated source must be preserved", source,
                WorkspaceWebsiteDuplicateFeedbackCleanup.review(fresh, source))
        }
    }
}
