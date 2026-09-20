package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteActionQualityTest {
    private fun source(goal: String) = WorkspaceWebsiteGeneration.Snapshot(
        "site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private fun files(html: String, css: String = "") = mapOf(
        "index.html" to html, "style.css" to css, "script.js" to "")

    @Test fun inertExploreButtonGetsNativeAnchorWithTargetAndVisibleFeedback() {
        val html = "<html><body><button class='cta' id='missing-listener'>Explore Minicoy</button>" +
            "<section><h2>Things to Explore</h2><p>Beaches</p></section></body></html>"
        val fixed = WorkspaceWebsiteActionQuality.review(
            source("Build Explore Minicoy button and Things to Explore section"), files(html))
        assertTrue(fixed.repaired)
        assertTrue(fixed.files.getValue("index.html").contains(
            "<a href=\"#lyra-explore-section\" class='cta'>Explore Minicoy</a>"))
        assertTrue(fixed.files.getValue("index.html").contains(
            "id=\"lyra-explore-section\" class=\"lyra-explore-target\""))
        assertTrue(fixed.files.getValue("style.css").contains(".lyra-explore-target:target"))
        assertFalse(fixed.files.getValue("index.html").contains("missing-listener"))
    }

    @Test fun existingAnchorAndTargetRemainStableAcrossSubsequentEdits() {
        val html = "<html><body><a href='#island'>Explore Minicoy</a>" +
            "<h2 id='island'>Things to Explore</h2></body></html>"
        val snapshot = source("Create Explore Minicoy CTA and Things to Explore")
        val first = WorkspaceWebsiteActionQuality.review(snapshot, files(html))
        assertEquals(1, Regex("""href=['\"]#island['\"]""").findAll(first.files.getValue("index.html")).count())
        assertEquals(1, Regex("""id=['\"]island['\"]""").findAll(first.files.getValue("index.html")).count())
        val second = WorkspaceWebsiteActionQuality.review(snapshot, first.files)
        assertFalse(second.repaired)
        assertEquals(first.files, second.files)
    }

    @Test fun explicitOtherClickActionAndUnrelatedButtonsAreNotOverwritten() {
        val html = "<html><body><button onclick='showWelcome()'>Explore Minicoy</button>" +
            "<h2>Things to Explore</h2><button>Book now</button></body></html>"
        val explicit = WorkspaceWebsiteActionQuality.review(source(
            "Explore Minicoy button click should show Welcome to Minicoy"), files(html))
        assertFalse(explicit.repaired)
        assertEquals(html, explicit.files.getValue("index.html"))
        val unrelated = WorkspaceWebsiteActionQuality.review(source("Add a booking button"), files(html))
        assertFalse(unrelated.repaired)
        assertEquals(html, unrelated.files.getValue("index.html"))
    }

    @Test fun intentionalSectionIdCollisionUsesNewIdWithoutChangingOtherElement() {
        val html = "<html><body><div id='lyra-explore-section'>Keep this</div>" +
            "<button class='cta'>Explore Minicoy</button><h2>Things to Explore</h2></body></html>"
        val fixed = WorkspaceWebsiteActionQuality.review(
            source("Explore Minicoy Things to Explore"), files(html))
        assertTrue(fixed.files.getValue("index.html").contains("href=\"#lyra-explore-section-1\""))
        assertTrue(fixed.files.getValue("index.html").contains("id='lyra-explore-section'"))
    }

    @Test fun preserveWholeThreeFileSnapshotAndVisualQualityReviewIntegration() {
        val html = "<html><head></head><body><button class='cta'>Explore Minicoy</button>" +
            "<h2>Things to Explore</h2></body></html>"
        val snapshot = source("Build an Explore Minicoy button and Things to Explore section")
        val original = files(html, ".cta{padding:1rem;}") + ("script.js" to "console.log('old')")
        val result = WorkspaceWebsiteVisualQuality.review(snapshot, original)
        assertTrue(result.repairedExploreAction)
        assertTrue(result.files.getValue("index.html").contains("href=\"#lyra-explore-section\""))
        assertEquals("console.log('old')", result.files.getValue("script.js"))
        assertTrue(result.chatNote().contains("tap it to verify"))
    }
}
