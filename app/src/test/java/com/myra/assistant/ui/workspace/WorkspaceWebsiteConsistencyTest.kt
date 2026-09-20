package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteConsistencyTest {
    private val brief = "Minicoy Island ke liye mobile-friendly website banao. " +
        "Welcome to Minicoy heading, Explore Minicoy button, " +
        "Things to Explore section, Beaches, Lighthouse aur Local Food ke 3 compact cards banao."
    private val html = "<html><head><meta name='viewport' content='width=device-width'></head><body>" +
        "<h1>Welcome to Minicoy</h1><a href='#places'>Explore Minicoy</a>" +
        "<h2 id='places'>Things to Explore</h2><h3>Beaches</h3><h3>Lighthouse</h3>" +
        "<h3>Local Food</h3></body></html>"
    private val css = ".lyra-explore-target:target { outline: 2px solid teal; }"
    private fun snapshot(goal: String = brief, original: String? = null) =
        WorkspaceWebsiteGeneration.Snapshot("site", "task", "approved", goal,
            mapOf("index.html" to original, "style.css" to null, "script.js" to null))
    private fun files(page: String = html, stylesheet: String = css) =
        mapOf("index.html" to page, "style.css" to stylesheet, "script.js" to "")

    @Test fun acceptsRequiredHeadingsThreeCardsAndLiveNativeAnchor() {
        val result = WorkspaceWebsiteVisualQuality.review(snapshot(), files())
        assertTrue(result.files.getValue("index.html").contains("href='#places'"))
        assertEquals("", result.files.getValue("script.js"))
        assertEquals(result.files, WorkspaceWebsiteConsistency.verify(snapshot(), result.files))
    }

    @Test fun refusesMissingCardWithoutSavingPartialWebsite() {
        val missing = html.replace("<h3>Local Food</h3>", "<p>Local Food</p>")
        assertTrue(runCatching {
            WorkspaceWebsiteConsistency.verify(snapshot(), files(missing))
        }.exceptionOrNull()?.message.orEmpty().contains("Local Food card"))
    }

    @Test fun refusesInertCtaAndDanglingOrDuplicateTargets() {
        val button = html.replace("<a href='#places'>Explore Minicoy</a>",
            "<button>Explore Minicoy</button>")
        assertTrue(runCatching { WorkspaceWebsiteConsistency.verify(snapshot(), files(button)) }.isFailure)
        assertTrue(runCatching { WorkspaceWebsiteConsistency.verify(snapshot(),
            files(html.replace("href='#places'", "href='#not-found'"))) }.isFailure)
        assertTrue(runCatching { WorkspaceWebsiteConsistency.verify(snapshot(),
            files(html.replace("<body>", "<body><span id='places'></span>"))) }.isFailure)
    }

    @Test fun actionQualityRepairFeedsTheSameProviderIndependentGate() {
        val bad = html.replace("<a href='#places'>Explore Minicoy</a>",
            "<button class='cta'>Explore Minicoy</button>")
        val checked = WorkspaceWebsiteVisualQuality.review(snapshot(), files(bad))
        assertTrue(checked.repairedExploreAction)
        assertTrue(checked.files.getValue("index.html").contains("href=\"#places\""))
        assertTrue(WorkspaceWebsiteConsistency.verify(snapshot(), checked.files).isNotEmpty())
    }

    @Test fun unrelatedProjectDoesNotInheritMinicoyRules() {
        val unrelated = "<html><body><h1>My portfolio</h1></body></html>"
        assertEquals(unrelated, WorkspaceWebsiteConsistency.verify(
            snapshot("Make a simple portfolio website"), files(unrelated)).getValue("index.html"))
    }

    @Test fun followupCannotSilentlyEraseExistingSectionAndCustomActionIsPreserved() {
        val original = snapshot("Change the Explore Minicoy button color", html)
        assertTrue(runCatching { WorkspaceWebsiteConsistency.verify(original,
            files(html.replace("<h2 id='places'>Things to Explore</h2>", ""))) }.isFailure)
        val alternate = snapshot("Explore Minicoy button click should show Welcome to Minicoy")
        val button = "<html><body><h1>Welcome to Minicoy</h1>" +
            "<button onclick='showWelcome()'>Explore Minicoy</button></body></html>"
        assertEquals(button, WorkspaceWebsiteConsistency.verify(alternate, files(button))
            .getValue("index.html"))
    }
}
