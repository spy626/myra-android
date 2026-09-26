package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteNativeActionOwnerTest {
    private val goal = "Minicoy Island ki mobile-friendly website banao. Blue hero, white Welcome to Minicoy, " +
        "orange Explore Minicoy button aur Beaches, Lighthouse, Local Food ke 3 compact pink cards. " +
        "Button tap par Things to Explore tak scroll ho aur Exploring Minicoy! text dikhaye."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private val html = """<!doctype html><html><head><link rel="stylesheet" href="style.css"></head><body>
        <h1>Welcome to Minicoy</h1><a href="#lyra-explore-section">Explore Minicoy</a>
        <h2 id="lyra-explore-section" class="lyra-explore-target">Things to Explore</h2>
        <p class="lyra-explore-feedback">Exploring Minicoy!</p>
        <h3>Beaches</h3><h3>Lighthouse</h3><h3>Local Food</h3>
        <script src="script.js"></script></body></html>""".trimIndent()
    private val css = ".lyra-explore-target:target + .lyra-explore-feedback { display: block; }"
    private val unfamiliarButRedundant = """document.addEventListener('DOMContentLoaded', () => {
        const link = document.querySelector('a[href="#lyra-explore-section"]');
        link.addEventListener('click', evt => {
          evt.preventDefault();
          document.querySelector('#lyra-explore-section').scrollIntoView({behavior:'smooth'});
        });
    });"""
    private fun files(js: String = unfamiliarButRedundant, markup: String = html) =
        mapOf("index.html" to markup, "style.css" to css, "script.js" to js)

    @Test fun freshExplicitOneActionSiteUsesNativeLinkInsteadOfUnverifiedCompetingJs() {
        val result = WorkspaceWebsiteConsistency.verify(fresh, files())
        assertTrue(result.getValue("index.html").contains("href=\"#lyra-explore-section\""))
        assertFalse(result.getValue("script.js").contains("preventDefault"))
        assertTrue(result.getValue("script.js").contains("LYRA omitted"))
        assertTrue(WorkspaceCodingResult.websiteSuccess(fresh.original, result)
            .contains("Explore button"))
        assertEquals(result, WorkspaceWebsiteConsistency.verify(fresh, result))
    }

    @Test fun networkSideEffectsAndUnrelatedControlsNeverGetSilentlyDiscarded() {
        val network = files(js = unfamiliarButRedundant + "\nfetch('/api/private');")
        assertTrue(runCatching { WorkspaceWebsiteConsistency.verify(fresh, network) }.isFailure)
        val unrelatedButton = files(markup = html.replace("<script src=", "<button id=\"checkout\">Pay</button><script src="))
        assertTrue(runCatching { WorkspaceWebsiteConsistency.verify(fresh, unrelatedButton) }.isFailure)
        val inline = files(markup = html.replace("Explore Minicoy</a>",
            "Explore Minicoy</a><span onclick=\"doSomething()\">X</span>"))
        assertTrue(runCatching { WorkspaceWebsiteConsistency.verify(fresh, inline) }.isFailure)
    }

    @Test fun existingSourceOtherTaskAndUnrelatedGeneratedJsArePreserved() {
        val old = fresh.copy(original = files().mapValues { it.value })
        assertEquals(files(), WorkspaceWebsiteNativeActionOwner.review(old, files()))
        assertEquals(files(), WorkspaceWebsiteNativeActionOwner.review(
            fresh.copy(goal = "Build a portfolio"), files()))
        assertEquals(files("console.log('other feature');"),
            WorkspaceWebsiteNativeActionOwner.review(fresh, files("console.log('other feature');")))
        val formGoal = fresh.copy(goal = goal + " Add a contact form that submits with JavaScript.")
        assertEquals(files(), WorkspaceWebsiteNativeActionOwner.review(formGoal, files()))
    }
}
