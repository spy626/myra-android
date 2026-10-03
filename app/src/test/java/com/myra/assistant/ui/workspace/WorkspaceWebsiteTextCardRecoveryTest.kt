package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteTextCardRecoveryTest {
    private val exactPhoneBrief = "Minicoy Island ki mobile-friendly website banao. Blue hero " +
        "section, white “Welcome to Minicoy” heading, orange “Explore Minicoy” button aur " +
        "Beaches, Lighthouse, Local Food ke 3 compact pink cards rakho. Button tap karne par " +
        "“Things to Explore” section tak scroll ho aur “Exploring Minicoy!” text clearly " +
        "dikhaye. Koi broken image ya empty photo box nahi. Files save karke Preview dikhao."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "spec", exactPhoneBrief,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private val beginning = "<html><head><link rel='stylesheet' href='style.css'></head>" +
        "<body><main><h1>Welcome to Minicoy</h1><button>Explore Minicoy</button>"
    private val end = "</main><script src='script.js'></script></body></html>"
    private fun output(body: String) = mapOf("index.html" to beginning + body + end,
        "style.css" to "body { margin: 0; }", "script.js" to "")

    @Test fun textOnlyCardsInOneGridRecoverWithoutDuplicatingTheirOriginalContent() {
        val proposed = output("<div class='compact-cards-grid'>" +
            "<article class='card'><strong>Beaches</strong><p>Coast</p></article>" +
            "<article class='card'><strong>Lighthouse</strong><p>Landmark</p></article>" +
            "<article class='card'><strong>Local Food</strong><p>Flavours</p></article></div>")
        val result = WorkspaceWebsiteVisualQuality.review(fresh, proposed)
        assertTrue(result.completedRequestedSection)
        assertTrue(result.rebuiltCardGroup)
        assertTrue(result.repairedExploreAction)
        val html = result.files.getValue("index.html")
        listOf("Beaches", "Lighthouse", "Local Food").forEach { title ->
            assertEquals(1, Regex("(?i)<h3>$title</h3>").findAll(html).count())
        }
        assertFalse(html.contains("<strong>Beaches</strong>"))
        assertTrue(result.files.getValue("style.css").contains("#fce7f3"))
        assertEquals(result.files, WorkspaceWebsiteConsistency.verify(fresh, result.files))
        assertEquals(result.files, WorkspaceWebsiteVisualQuality.review(fresh, result.files).files)
        assertTrue(result.chatNote().contains("rebuilt only", true))
    }

    @Test fun trailingEmojiOnHeadingAndCardsDoesNotCauseFalseMissingSection() {
        val result = WorkspaceWebsiteVisualQuality.review(fresh,
            output("<section><h2>Things to Explore 🌴</h2>" +
                "<h3>Beaches 🏖</h3><h3>Lighthouse 🗼</h3>" +
                "<h3>Local Food 🍲</h3></section>"))
        assertFalse(result.completedRequestedSection)
        assertTrue(result.repairedExploreAction)
        assertEquals(1, Regex("Things to Explore").findAll(result.files.getValue("index.html")).count())
        assertEquals(result.files, WorkspaceWebsiteConsistency.verify(fresh, result.files))
    }

    @Test fun paragraphMentionsAreNotActualCardsAndDoNotBlockNewSection() {
        val result = WorkspaceWebsiteVisualQuality.review(fresh,
            output("<p>Read more about Beaches, Lighthouse and Local Food below.</p>"))
        assertTrue(result.completedRequestedSection)
        assertFalse(result.rebuiltCardGroup)
        assertEquals(3, Regex("class=\"lyra-requested-explore-card\"")
            .findAll(result.files.getValue("index.html")).count())
        assertEquals(result.files, WorkspaceWebsiteConsistency.verify(fresh, result.files))
    }

    @Test fun scatteredCardGroupsStayUnchangedWithSafeDiagnostic() {
        val proposed = output("<div class='cards-grid'><h3>Beaches</h3></div>" +
            "<div class='cards-grid'><h3>Lighthouse</h3></div>")
        val outcome = WorkspaceWebsiteRequestedSectionRepair.repair(fresh, proposed)
        assertEquals("NO_UNAMBIGUOUS_CARD_GROUP", outcome.diagnostic)
        assertFalse(outcome.completed)
        assertEquals(proposed, outcome.files)
        val error = runCatching { WorkspaceWebsiteVisualQuality.review(fresh, proposed) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("local repair: NO_UNAMBIGUOUS_CARD_GROUP"))
        assertFalse(error.message.orEmpty().contains("<div"))
    }

    @Test fun existingSiteAndUnrelatedBriefNeverGetReconstructed() {
        val proposed = output("<div class='cards-grid'>Beaches Lighthouse Local Food</div>")
        val existing = fresh.copy(original = proposed.mapValues { it.value })
        val old = WorkspaceWebsiteRequestedSectionRepair.repair(existing, proposed)
        assertFalse(old.completed)
        assertEquals("EXISTING_PROJECT", old.diagnostic)
        assertEquals(proposed, old.files)
        val unrelated = fresh.copy(goal = "Build a mobile shop website with product cards")
        val other = WorkspaceWebsiteRequestedSectionRepair.repair(unrelated, proposed)
        assertFalse(other.completed)
        assertEquals("BRIEF_NOT_ELIGIBLE", other.diagnostic)
        assertEquals(proposed, other.files)
    }
}
