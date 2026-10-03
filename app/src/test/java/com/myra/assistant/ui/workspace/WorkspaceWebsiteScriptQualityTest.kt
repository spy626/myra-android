package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteScriptQualityTest {
    private val goal = "Minicoy Island website banao. Welcome to Minicoy, Explore Minicoy button " +
        "aur Things to Explore section. Button tap par Exploring Minicoy! dikhaye."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private val generatedHtml = """<html><head><link rel="stylesheet" href="style.css"></head><body>
        <h1>Welcome to Minicoy</h1><button id="exploreBtn">Explore Minicoy</button>
        <section id="things-to-explore"><h2>Things to Explore</h2></section>
        <script src="script.js"></script></body></html>""".trimIndent()
    // Mirrors the isolated saved script visible in the actual phone recording.
    private val recordedScript = """
        // Smooth scroll for the Explore button
        document.getElementById('exploreBtn').addEventListener('click', function(e) {
            e.preventDefault();
            const target = document.getElementById('things-to-explore');
            if (target) {
                target.scrollIntoView({ behavior: 'smooth' });
                // Optionally set focus for accessibility
                target.setAttribute('tabindex', '-1');
                target.focus();
            }
        });
    """.trimIndent()
    private fun generated(script: String = recordedScript) = mapOf(
        "index.html" to generatedHtml, "style.css" to "body { margin: 0; }", "script.js" to script)

    @Test fun recordedButtonReplacementDropsOnlyRedundantListenerAndKeepsNativeFeedback() {
        val result = WorkspaceWebsiteVisualQuality.review(fresh, generated())
        val html = result.files.getValue("index.html")
        val js = result.files.getValue("script.js")
        assertTrue(html.contains("href=\"#lyra-explore-section\""))
        assertTrue(html.contains("class=\"lyra-explore-feedback\""))
        assertFalse(html.contains("id=\"exploreBtn\""))
        assertFalse(js.contains("getElementById"))
        assertFalse(js.contains("preventDefault"))
        assertTrue(js.contains("native in-page link"))
        assertEquals(result.files, WorkspaceWebsiteVisualQuality.review(fresh, result.files).files)
    }

    @Test fun unknownMissingListenerFailsClosedRatherThanSilentlyDeletingBehavior() {
        val unknown = generated("document.getElementById('exploreBtn').addEventListener('click', () => alert('hi'));\n")
        assertTrue(runCatching { WorkspaceWebsiteVisualQuality.review(fresh, unknown) }.isFailure)
        val unrelated = generated("document.getElementById('checkoutBtn').addEventListener('click', fn);\n")
        assertTrue(runCatching { WorkspaceWebsiteVisualQuality.review(fresh, unrelated) }.isFailure)
        assertEquals(unknown.getValue("script.js"), generated(unknown.getValue("script.js")).getValue("script.js"))
    }

    @Test fun preexistingSiteNeverHasItsScriptDiscarded() {
        val generated = generated()
        val established = fresh.copy(original = generated.mapValues { it.value })
        val alreadyNative = WorkspaceWebsiteActionQuality.review(fresh, generated).files
        assertTrue(runCatching { WorkspaceWebsiteScriptQuality.review(established, alreadyNative) }.isFailure)
    }

    @Test fun matchingHtmlIdsAndUnrelatedScriptsRemainUntouched() {
        val withMatchingButton = generatedHtml.replace("<button id=\"exploreBtn\">", "<button id=\"exploreBtn\">")
        val files = generated().plus("index.html" to withMatchingButton)
        assertEquals(files, WorkspaceWebsiteScriptQuality.review(fresh, files))
        assertEquals(generated("console.log('safe');"),
            WorkspaceWebsiteScriptQuality.review(fresh, generated("console.log('safe');")))
    }
}
