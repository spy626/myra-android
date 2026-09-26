package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteRequestedSectionRepairTest {
    private val goal = "Minicoy Island ki mobile-friendly website banao. Blue hero, white " +
        "Welcome to Minicoy heading, orange Explore Minicoy button, Beaches, Lighthouse, " +
        "Local Food ke 3 compact pink cards. Things to Explore section tak scroll ho aur " +
        "Exploring Minicoy! text dikhe."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private val page = "<html><head></head><body><main><h1>Welcome to Minicoy</h1>" +
        "<button>Explore Minicoy</button></main></body></html>"
    private fun output(html: String = page) = mapOf(
        "index.html" to html, "style.css" to "body { margin: 0; }", "script.js" to "")

    @Test fun incompleteNewSiteGetsRequestedCardsAndWorkingSectionLinkWithoutExtraProvider() {
        val review = WorkspaceWebsiteVisualQuality.review(fresh, output())
        val html = review.files.getValue("index.html")
        assertTrue(review.completedRequestedSection)
        assertTrue(review.repairedExploreAction)
        listOf("Things to Explore", "Beaches", "Lighthouse", "Local Food").forEach {
            assertTrue(html.contains(">$it<"))
        }
        assertEquals(3, Regex("lyra-requested-explore-card\"").findAll(html).count())
        assertTrue(html.contains("href=\"#"))
        assertTrue(html.contains("Exploring Minicoy!"))
        assertTrue(review.files.getValue("style.css").contains("#fce7f3"))
        assertEquals(review.files, WorkspaceWebsiteConsistency.verify(fresh, review.files))
        assertTrue(review.chatNote().contains("completed", ignoreCase = true))
    }

    @Test fun ctaOrParagraphTextCannotMasqueradeAsRequestedSection() {
        val misleading = page.replace("</main>",
            "<p>Tap Explore Minicoy to jump to Things to Explore.</p>" +
            "<section class='tiles'><article><h3>Beaches</h3></article>" +
            "<article><h3>Lighthouse</h3></article>" +
            "<article><h3>Local Food</h3></article></section></main>")
        val review = WorkspaceWebsiteVisualQuality.review(fresh, output(misleading))
        val html = review.files.getValue("index.html")
        assertTrue(review.completedRequestedSection)
        assertTrue(review.repairedExploreAction)
        assertEquals(1, Regex("<h2[^>]*>Things to Explore</h2>").findAll(html).count())
        listOf("Beaches", "Lighthouse", "Local Food").forEach { name ->
            assertEquals(1, Regex("<h3>$name</h3>").findAll(html).count())
        }
        assertEquals(review.files, WorkspaceWebsiteConsistency.verify(fresh, review.files))
        assertEquals(review.files, WorkspaceWebsiteVisualQuality.review(fresh, review.files).files)

        val existing = fresh.copy(original = output(misleading).mapValues { it.value })
        assertFalse(WorkspaceWebsiteRequestedSectionRepair.repair(existing, output(misleading)).completed)
        assertTrue(runCatching { WorkspaceWebsiteVisualQuality.review(existing, output(misleading)) }.isFailure)
    }

    @Test fun nestedDivGridGetsSectionHeadingWithoutDuplicatingValidCards() {
        // Reproduces the previously unhandled common model layout: div.card-grid, not section.
        val grid = page.replace("</main>", "<div class='compact-card-grid'>" +
            "<div class='card'><h3>Beaches</h3></div>" +
            "<div class='card'><h3>Lighthouse</h3></div>" +
            "<div class='card'><h3>Local Food</h3></div></div></main>")
        val review = WorkspaceWebsiteVisualQuality.review(fresh, output(grid))
        val html = review.files.getValue("index.html")
        assertTrue(review.completedRequestedSection)
        assertTrue(review.repairedExploreAction)
        assertTrue(html.indexOf(">Things to Explore</h2>") < html.indexOf("<div class='compact-card-grid'>"))
        listOf("Beaches", "Lighthouse", "Local Food").forEach { card ->
            assertEquals(1, Regex(">$card</h3>").findAll(html).count())
        }
        assertEquals(review.files, WorkspaceWebsiteConsistency.verify(fresh, review.files))
        assertEquals(review.files, WorkspaceWebsiteVisualQuality.review(fresh, review.files).files)
    }

    @Test fun oneMissingCardInUnambiguousGridIsCompletedLocally() {
        val partial = page.replace("</main>", "<nav>Local Food</nav><div class='cards-grid'>" +
            "<article><h3>Beaches</h3></article><article><h3>Lighthouse</h3></article>" +
            "</div></main>")
        val review = WorkspaceWebsiteVisualQuality.review(fresh, output(partial))
        val html = review.files.getValue("index.html")
        assertTrue(review.completedRequestedSection)
        assertTrue(review.repairedExploreAction)
        assertEquals(1, Regex(">Beaches</h3>").findAll(html).count())
        assertEquals(1, Regex(">Lighthouse</h3>").findAll(html).count())
        assertEquals(1, Regex(">Local Food</h3>").findAll(html).count())
        assertTrue(html.contains("<article class=\"lyra-requested-explore-card\"><h3>Local Food</h3>"))
        assertTrue(review.files.getValue("style.css").contains("#fce7f3"))
        assertEquals(review.files, WorkspaceWebsiteConsistency.verify(fresh, review.files))
    }

    @Test fun existingCardSectionGetsHeadingWithoutDuplicatedCards() {
        val grouped = page.replace("</main>", "<section class='tiles'>" +
            "<article><h3>Beaches</h3></article>" +
            "<article><h3>Lighthouse</h3></article>" +
            "<article><h3>Local Food</h3></article></section></main>")
        val review = WorkspaceWebsiteVisualQuality.review(fresh, output(grouped))
        assertTrue(review.completedRequestedSection)
        assertEquals(1, Regex(">Beaches<").findAll(review.files.getValue("index.html")).count())
        assertEquals(1, Regex(">Things to Explore<").findAll(review.files.getValue("index.html")).count())
    }

    @Test fun decoratedHeadingsCountAsRequestedContentWithoutDuplicatingSection() {
        val decorated = page.replace("</main>", "<section>" +
            "<h2>🌴 Things to Explore</h2><h3>🏖 Beaches</h3>" +
            "<h3>🗼 Lighthouse</h3><h3>🍲 Local Food</h3></section></main>")
        val review = WorkspaceWebsiteVisualQuality.review(fresh, output(decorated))
        assertFalse(review.completedRequestedSection)
        assertTrue(review.repairedExploreAction)
        assertEquals(1, Regex("Things to Explore").findAll(review.files.getValue("index.html")).count())
        assertEquals(review.files, WorkspaceWebsiteConsistency.verify(fresh, review.files))
    }

    @Test fun existingSiteOrAmbiguousMarkupIsNeverSilentlyRewritten() {
        val existing = fresh.copy(original = mapOf("index.html" to page,
            "style.css" to "body{}", "script.js" to ""))
        assertFalse(WorkspaceWebsiteRequestedSectionRepair.repair(existing, output()).completed)
        assertTrue(runCatching { WorkspaceWebsiteVisualQuality.review(existing, output()) }.isFailure)
        val ambiguous = page.replace("</main>", "<h3>Beaches</h3></main>")
        assertFalse(WorkspaceWebsiteRequestedSectionRepair.repair(fresh, output(ambiguous)).completed)
        assertTrue(runCatching { WorkspaceWebsiteVisualQuality.review(fresh, output(ambiguous)) }.isFailure)
        val scattered = page.replace("</main>", "<div class='cards-grid'>" +
            "<h3>Beaches</h3><h3>Lighthouse</h3></div>" +
            "<section><h3>Local Food</h3></section></main>")
        assertFalse(WorkspaceWebsiteRequestedSectionRepair.repair(fresh, output(scattered)).completed)
        assertTrue(runCatching { WorkspaceWebsiteVisualQuality.review(fresh, output(scattered)) }.isFailure)
        val established = fresh.copy(original = output(scattered).mapValues { it.value })
        assertFalse(WorkspaceWebsiteRequestedSectionRepair.repair(established, output(scattered)).completed)
    }

    @Test fun otherProjectsAndAlreadyCompleteProjectsNeedNoRecovery() {
        val unrelated = fresh.copy(goal = "Make a portfolio site with three cards")
        assertFalse(WorkspaceWebsiteRequestedSectionRepair.repair(unrelated, output()).completed)
        val full = page.replace("</main>", "<h2>Things to Explore</h2>" +
            "<h3>Beaches</h3><h3>Lighthouse</h3><h3>Local Food</h3></main>")
        assertFalse(WorkspaceWebsiteRequestedSectionRepair.repair(fresh, output(full)).completed)
        assertTrue(WorkspaceWebsiteVisualQuality.review(fresh, output(full)).files.isNotEmpty())
    }
}
