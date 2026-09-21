package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

/** Reproduces the saved HTML/CSS structure visible in the 2026-09-21 13:02 phone test.
 * No private project content, account data or provider response is included.
 */
class WorkspaceWebsiteScopedFeedbackPhoneRegressionTest {
    private val goal = "Minicoy Island ki mobile-friendly website banao. Blue hero section, " +
        "white Welcome to Minicoy heading, orange Explore Minicoy button aur Beaches, " +
        "Lighthouse, Local Food ke 3 compact pink cards rakho. Button tap karne par " +
        "Things to Explore section tak scroll ho aur Exploring Minicoy! text clearly dikhaye."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="style.css"></head><body>
        <header class="hero"><h1>Welcome to Minicoy</h1><a href="#lyra-explore-section" class="btn">Explore Minicoy</a></header>
        <section class="cards"><article><h3>Beaches</h3></article><article><h3>Lighthouse</h3></article><article><h3>Local Food</h3></article></section>
        <section id="explore-section" class="explore">
        <h2 id="lyra-explore-section" class="lyra-explore-target">Things to Explore</h2><p class="lyra-explore-feedback" role="status" aria-live="polite">Exploring Minicoy!</p>
        <p class="feedback">Exploring Minicoy!</p><p>Discover the pristine beaches, historic lighthouse, and delicious local cuisine.</p>
        </section><script src="script.js"></script></body></html>""".trimIndent()
    private val css = """body { color:#123; }
        .hero { background:#087e93; }
        .explore { padding:1rem; text-align:center; }
        .explore .feedback {display:none; font-size:1.2rem; color:#fb923c; margin-bottom:0.5rem;}
        #explore-section:target .feedback {display:block;}
        /* LYRA Explore target feedback */
        .lyra-explore-target:target { outline:2px solid #0f766e; }
        .lyra-explore-feedback { display:none; }
        .lyra-explore-target:target + .lyra-explore-feedback { display:block; }
        """.trimIndent()
    private fun files(page: String = html, styles: String = css, js: String = "") =
        mapOf("index.html" to page, "style.css" to styles, "script.js" to js)

    @Test fun phoneScopedCssIsCleanedThroughRealPipelineAndNativeFeedbackSurvives() {
        val reviewed = WorkspaceWebsiteVisualQuality.review(fresh, files()).files
        val page = reviewed.getValue("index.html")
        val styles = reviewed.getValue("style.css")
        assertEquals(1, Regex("Exploring Minicoy!").findAll(page).count())
        assertFalse(page.contains("class=\"feedback\""))
        assertFalse(styles.contains(".explore .feedback"))
        assertFalse(styles.contains("#explore-section:target .feedback"))
        assertTrue(page.contains("href=\"#lyra-explore-section\""))
        assertTrue(page.contains("class=\"lyra-explore-feedback\" role=\"status\""))
        assertTrue(styles.contains(".lyra-explore-target:target + .lyra-explore-feedback"))
        assertTrue(page.contains("Discover the pristine beaches, historic lighthouse"))
        assertTrue(styles.contains(".hero { background:#087e93; }"))
        assertEquals(reviewed, WorkspaceWebsiteConsistency.verify(fresh, reviewed))
        assertEquals(reviewed, WorkspaceWebsiteVisualQuality.review(fresh, reviewed).files)
    }

    @Test fun scopedCleanupDoesNotTouchUnmatchedProjectMarkupOrScript() {
        val source = files()
        val established = fresh.copy(original = source.mapValues { it.value })
        assertEquals(source, WorkspaceWebsiteDuplicateFeedbackCleanup.review(established, source))
        val wrongSectionClass = files(page = html.replace("class=\"explore\"", "class=\"other\""))
        assertEquals(wrongSectionClass, WorkspaceWebsiteDuplicateFeedbackCleanup.review(fresh, wrongSectionClass))
        val wrongSectionId = files(page = html.replace("id=\"explore-section\"", "id=\"other-section\""))
        assertEquals(wrongSectionId, WorkspaceWebsiteDuplicateFeedbackCleanup.review(fresh, wrongSectionId))
        val groupedRule = files(styles = css.replace(".explore .feedback {", ".explore .feedback, .hint {"))
        assertEquals(groupedRule, WorkspaceWebsiteDuplicateFeedbackCleanup.review(fresh, groupedRule))
        val extraCssConsumer = files(styles = css + "\n.sidebar .feedback { color:red; }")
        assertEquals(extraCssConsumer, WorkspaceWebsiteDuplicateFeedbackCleanup.review(fresh, extraCssConsumer))
        val unknownScript = files(js = "document.querySelector('.feedback').textContent = 'Other';")
        assertEquals(unknownScript, WorkspaceWebsiteDuplicateFeedbackCleanup.review(fresh, unknownScript))
        val nestedSection = files(page = html.replace("<p class=\"feedback\">",
            "<section><p>Other content</p></section><p class=\"feedback\">"))
        assertEquals(nestedSection, WorkspaceWebsiteDuplicateFeedbackCleanup.review(fresh, nestedSection))
    }
}
