package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteConsistencyRemovalTest {
    private val old = "<html><body><h1>Welcome to Minicoy</h1>" +
        "<h2>Things to Explore</h2><h3>Local Food</h3></body></html>"
    private fun snapshot(goal: String) = WorkspaceWebsiteGeneration.Snapshot(
        "site", "task", "approved", goal,
        mapOf("index.html" to old, "style.css" to null, "script.js" to null))
    private fun files(html: String) = mapOf("index.html" to html,
        "style.css" to "", "script.js" to "")

    @Test fun explicitlyRemovingWelcomeDoesNotRequireItsReturn() {
        val page = old.replace("<h1>Welcome to Minicoy</h1>", "")
        assertEquals(page, WorkspaceWebsiteConsistency.verify(
            snapshot("Remove Welcome to Minicoy heading"), files(page))["index.html"])
    }

    @Test fun removingFoodMustNotPermitUnrelatedSectionToDisappear() {
        val page = old.replace("<h2>Things to Explore</h2>", "")
        assertTrue(runCatching { WorkspaceWebsiteConsistency.verify(
            snapshot("Remove the Local Food card, keep Welcome to Minicoy and Things to Explore"),
            files(page)) }.isFailure)
    }
}
