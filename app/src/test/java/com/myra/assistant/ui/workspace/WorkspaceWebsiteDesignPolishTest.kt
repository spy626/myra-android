package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteDesignPolishTest {
    private val goal = "Minicoy Island ki mobile-friendly website banao. Blue hero section, " +
        "white Welcome to Minicoy heading, orange Explore Minicoy button aur Beaches, " +
        "Lighthouse, Local Food ke 3 compact pink cards rakho. Button tap karne par " +
        "Things to Explore section tak scroll ho aur Exploring Minicoy! text clearly dikhaye. " +
        "Koi broken image ya empty photo box nahi. Files save karke Preview dikhao."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    // Mirrors the visible saved HTML from the phone recording, without any user secrets.
    private val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="style.css"></head><body>
        <header class="hero"><h1 class="hero-title">Welcome to Minicoy</h1><a class="cta-button" href="#things-to-explore">Explore Minicoy</a></header>
        <section class="cards-section"><h2>Things to Explore</h2><div class="lyra-requested-explore-cards">
        <article class="lyra-requested-explore-card"><h3>Beaches</h3><p>Explore coastal scenery.</p></article>
        <article class="lyra-requested-explore-card"><h3>Lighthouse</h3><p>Learn about an island landmark.</p></article>
        <article class="lyra-requested-explore-card"><h3>Local Food</h3><p>Discover island flavours.</p></article></div></section>
        <section class="explore-section" id="things-to-explore"><h2>Exploring Minicoy!</h2><p>Discover island experiences.</p></section>
        <script src="script.js"></script></body></html>""".trimIndent()
    private val css = ".hero{background:#087e93}.cards-section{display:grid;grid-template-columns:1fr 1fr;min-height:400px}" +
        ".explore-section{min-height:350px}.lyra-requested-explore-cards{display:grid;grid-template-columns:repeat(3,1fr)}"
    private fun output(page: String = html, styles: String = css) =
        mapOf("index.html" to page, "style.css" to styles, "script.js" to "")

    @Test fun recordingShapedNewSiteGetsBlueHeroFullWidthMobileCardsAndSingleClickFeedback() {
        val review = WorkspaceWebsiteVisualQuality.review(fresh, output())
        val page = review.files.getValue("index.html")
        val styles = review.files.getValue("style.css")
        assertTrue(review.polishedDesign)
        assertTrue(page.contains("hero lyra-requested-blue-hero"))
        assertTrue(page.contains("cards-section lyra-requested-explore"))
        assertTrue(page.contains("explore-section lyra-requested-explore-copy"))
        assertFalse(page.contains("<h2>Exploring Minicoy!</h2>"))
        assertEquals(1, Regex("Exploring Minicoy!").findAll(page).count())
        assertTrue(page.contains("Discover island experiences."))
        assertTrue(styles.contains("background: #2157c8 !important"))
        assertTrue(styles.contains("grid-template-columns: minmax(0, 1fr) !important"))
        assertTrue(styles.contains("display: block !important; width: 100% !important"))
        assertTrue(styles.contains("@media (min-width: 720px)"))
        assertTrue(page.contains("href=\"#lyra-explore-section\""))
        assertEquals(review.files, WorkspaceWebsiteConsistency.verify(fresh, review.files))
        assertTrue(review.chatNote().contains("blue hero"))
    }

    @Test fun polishIsIdempotentAndDoesNotReintroduceFeedback() {
        val first = WorkspaceWebsiteVisualQuality.review(fresh, output())
        val again = WorkspaceWebsiteVisualQuality.review(fresh, first.files)
        assertEquals(first.files, again.files)
        assertFalse(again.polishedDesign)
        assertEquals(1, Regex("Exploring Minicoy!").findAll(again.files.getValue("index.html")).count())
    }

    @Test fun establishedSiteAndUnrelatedBriefNeverGetDesignOverridden() {
        val established = fresh.copy(original = output().mapValues { it.value })
        assertEquals(output(), WorkspaceWebsiteDesignPolish.review(established, output()).files)
        assertFalse(WorkspaceWebsiteDesignPolish.review(established, output()).changed)
        val unrelated = fresh.copy(goal = "Make a blue portfolio website with pink cards")
        assertEquals(output(), WorkspaceWebsiteDesignPolish.review(unrelated, output()).files)
        val noExplicitBlue = fresh.copy(goal = goal.replace("Blue hero", "Coastal hero"))
        assertEquals(output(), WorkspaceWebsiteDesignPolish.review(noExplicitBlue, output()).files)
    }

    @Test fun ambiguousOrOversizedMarkupFailsClosedWithoutDroppingExistingContent() {
        val noHero = output(html.replace("<header class=\"hero\">", "<div class=\"hero\">")
            .replace("</header>", "</div>"))
        assertEquals(noHero, WorkspaceWebsiteDesignPolish.review(fresh, noHero).files)
        val duplicate = output(html.replace("</section>\n        <section class=\"explore-section\"",
            "</section><section><h2>Things to Explore</h2><h3>Beaches</h3>" +
                "<h3>Lighthouse</h3><h3>Local Food</h3></section>\n        <section class=\"explore-section\""))
        assertEquals(duplicate, WorkspaceWebsiteDesignPolish.review(fresh, duplicate).files)
        val huge = output(styles = css + "x".repeat(14_700))
        assertEquals(huge, WorkspaceWebsiteDesignPolish.review(fresh, huge).files)
    }
}
