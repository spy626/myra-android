package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

/** Replays the saved 2026-09-21 phone page, including its separate cards/explore sections. */
class WorkspaceWebsiteSplitSectionPhoneRegressionTest {
    private val goal = "Minicoy Island ki mobile-friendly website banao. Blue hero section, " +
        "white Welcome to Minicoy heading, orange Explore Minicoy button aur Beaches, " +
        "Lighthouse, Local Food ke 3 compact pink cards rakho. Button tap karne par " +
        "Things to Explore section tak scroll ho aur Exploring Minicoy! text clearly dikhaye."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private val html = """<!DOCTYPE html>
        <html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><link rel="stylesheet" href="style.css"></head><body>
        <header class="hero"><h1 class="welcome">Welcome to Minicoy</h1><a href="#lyra-explore-section" class="btn">Explore Minicoy</a></header>
        <section class="cards"><div class="lyra-requested-explore-cards"><article class="lyra-requested-explore-card"><h3>Beaches</h3><p>Explore coastal scenery.</p></article>
        <article class="lyra-requested-explore-card"><h3>Lighthouse</h3><p>Learn about an island landmark.</p></article>
        <article class="lyra-requested-explore-card"><h3>Local Food</h3><p>Discover island flavours.</p></article></div></section>
        <section id="explore-section" class="explore"><h2 id="lyra-explore-section" class="lyra-explore-target">Things to Explore</h2><p class="lyra-explore-feedback" role="status" aria-live="polite">Exploring Minicoy!</p>
        <p>Discover the pristine beaches, historic lighthouse, and delicious local cuisine.</p></section>
        <script src="script.js"></script></body></html>""".trimIndent()
    private val css = """body {margin:0;} .hero{background:#087e93;color:white;} .cards{display:flex; flex-direction:column;} .explore{padding:1rem;}
        /* Explicit requested section; new website only */
        .lyra-requested-explore-cards {display:grid;grid-template-columns:repeat(3,1fr);}
        .lyra-requested-explore-card{background:#fce7f3;}
        /* LYRA Explore target feedback */
        .lyra-explore-target:target {outline:2px solid #0f766e;}
        .lyra-explore-feedback {display:none;}
        .lyra-explore-target:target + .lyra-explore-feedback {display:block;background:#d1fae5;}
        """.trimIndent()
    private fun files(page: String = html, style: String = css, js: String = "") =
        mapOf("index.html" to page, "style.css" to style, "script.js" to js)

    @Test fun realPipelinePreservesOneClickFeedbackAndMakesRequestedBlueHeroAboveCards() {
        val first = WorkspaceWebsiteVisualQuality.review(fresh, files())
        val page = first.files.getValue("index.html")
        val styles = first.files.getValue("style.css")
        assertTrue("Blue polish must actually run", first.polishedDesign)
        assertTrue(page.contains("hero lyra-requested-blue-hero"))
        assertTrue(styles.contains("background: #2157c8 !important"))
        assertTrue(styles.contains("grid-template-columns: minmax(0, 1fr) !important"))
        assertTrue(page.contains("class=\"explore cards lyra-requested-explore\""))
        assertTrue(page.indexOf("Things to Explore") < page.indexOf("<h3>Beaches</h3>"))
        assertTrue(page.indexOf("<h3>Beaches</h3>") < page.indexOf("<h3>Lighthouse</h3>"))
        assertTrue(page.indexOf("<h3>Lighthouse</h3>") < page.indexOf("<h3>Local Food</h3>"))
        assertEquals(1, Regex("Exploring Minicoy!").findAll(page).count())
        assertEquals(1, Regex("lyra-explore-feedback").findAll(page).count())
        assertTrue(page.contains("href=\"#lyra-explore-section\""))
        assertTrue(page.contains("Discover the pristine beaches, historic lighthouse"))
        assertTrue(page.contains("lagoon-side shores"))
        assertEquals(first.files, WorkspaceWebsiteVisualQuality.review(fresh, first.files).files)
        assertEquals(first.files, WorkspaceWebsiteConsistency.verify(fresh, first.files))
    }

    @Test fun deadOpacityFeedbackIsRemovedBeforeSectionsMove() {
        val page = html.replace("<p>Discover the pristine beaches",
            "<p class=\"feedback\">Exploring Minicoy!</p><p>Discover the pristine beaches")
        val styles = css + "\n.explore .feedback {opacity:0; transition:opacity 0.2s;}" +
            "\n#explore-section:target .feedback {opacity:1;}\n"
        val result = WorkspaceWebsiteVisualQuality.review(fresh, files(page, styles)).files
        assertEquals(1, Regex("Exploring Minicoy!").findAll(result.getValue("index.html")).count())
        assertFalse(result.getValue("style.css").contains("#explore-section:target .feedback"))
        assertTrue(result.getValue("index.html").contains("hero lyra-requested-blue-hero"))
    }

    @Test fun existingAndAmbiguousOrScriptedPagesAreNotReordered() {
        val source = files()
        assertEquals(source, WorkspaceWebsiteSplitSectionPolish.review(
            fresh.copy(original = source.mapValues { it.value }), source))
        val cases = listOf(
            files(page = html.replace("href=\"#lyra-explore-section\"", "href=\"#other\"")),
            files(page = html.replace("class=\"explore\"", "class=\"other\"")),
            files(page = html.replace("<section class=\"cards\">", "<section class=\"other\">")),
            files(page = html.replace("</div></section>", "</div><p>Other card content</p></section>")),
            files(page = html.replace("<article class=\"lyra-requested-explore-card\"><h3>Beaches",
                "<article class=\"lyra-requested-explore-card\"><h3>Other")),
            files(page = html.replace("</section>\n        <section id=\"explore-section\"",
                "</section><aside>Other content</aside><section id=\"explore-section\"")),
            files(js = "document.querySelector('.cards').remove();"),
            files(style = css.replace(".lyra-explore-target:target + .lyra-explore-feedback", ".other"))
        )
        cases.forEach { assertEquals("Unknown source must stay byte-for-byte intact", it,
            WorkspaceWebsiteSplitSectionPolish.review(fresh, it)) }
    }
}
