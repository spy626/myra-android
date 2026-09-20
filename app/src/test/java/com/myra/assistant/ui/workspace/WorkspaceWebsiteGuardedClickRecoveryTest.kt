package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteGuardedClickRecoveryTest {
    private val goal = "Minicoy Island ki website banao. Welcome to Minicoy heading, " +
        "Explore Minicoy button aur Things to Explore section. Button tap par Exploring Minicoy! dikhaye."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private val originalHtml = """<html><head><link rel="stylesheet" href="style.css"></head><body>
        <h1>Welcome to Minicoy</h1><button id="exploreBtn">Explore Minicoy</button>
        <section id="things-to-explore"><h2>Things to Explore</h2></section>
        <script src="script.js"></script></body></html>""".trimIndent()
    private fun source(js: String, html: String = originalHtml) = mapOf(
        "index.html" to html, "style.css" to "body { margin: 0; }", "script.js" to js)

    // The complete single-purpose DOMContentLoaded, guarded button and highlight pattern
    // visible in the physical Android recording, including the inline comment.
    private val observedGuardedScript = """
        // Smooth scroll for the Explore Minicoy button
        document.addEventListener('DOMContentLoaded', function() {
            const btn = document.getElementById('exploreBtn');
            if (btn) {
                btn.addEventListener('click', function(e) {
                    e.preventDefault();
                    const target = document.querySelector(this.getAttribute('href'));
                    if (target) {
                        target.scrollIntoView({ behavior: 'smooth' });
                        // Optional visual feedback for target
                        target.style.transition = 'background 0.5s';
                        const original = target.style.backgroundColor;
                        target.style.backgroundColor = '#fff3cd'; // light highlight
                        setTimeout(() => {
                            target.style.backgroundColor = original;
                        }, 800);
                    }
                });
            }
        });
    """.trimIndent()

    @Test fun recordedGuardedDomReadyScriptIsReconciledToNativeExploreLink() {
        val result = WorkspaceWebsiteVisualQuality.review(fresh, source(observedGuardedScript))
        val html = result.files.getValue("index.html")
        val js = result.files.getValue("script.js")
        assertTrue(html.contains("href=\"#lyra-explore-section\""))
        assertTrue(html.contains("class=\"lyra-explore-feedback\""))
        assertFalse(html.contains("id=\"exploreBtn\""))
        assertFalse(js.contains("preventDefault"))
        assertFalse(js.contains("setTimeout"))
        assertTrue(js.contains("native in-page link"))
        assertEquals(result.files, WorkspaceWebsiteConsistency.verify(fresh, result.files))
        assertEquals(result.files, WorkspaceWebsiteVisualQuality.review(fresh, result.files).files)
    }

    @Test fun ordinaryGuardedListenerIsReconciledButUnknownListenerIsRejected() {
        val simple = """
            const button = document.getElementById('exploreBtn');
            if (button) { button.addEventListener('click', function(event) {
                event.preventDefault();
                const section = document.getElementById('things-to-explore');
                if (section) { section.scrollIntoView({ behavior: 'smooth' }); }
            }); }
        """.trimIndent()
        val repaired = WorkspaceWebsiteVisualQuality.review(fresh, source(simple))
        assertFalse(repaired.files.getValue("script.js").contains("getElementById"))
        val extra = simple + "\nfetch('https://example.org/unapproved');"
        assertTrue(runCatching { WorkspaceWebsiteVisualQuality.review(fresh, source(extra)) }.isFailure)
        val unknown = "const btn = document.getElementById('exploreBtn'); " +
            "if (btn) btn.addEventListener('click', () => alert('other action'));"
        assertTrue(runCatching { WorkspaceWebsiteVisualQuality.review(fresh, source(unknown)) }.isFailure)
    }

    @Test fun unfamiliarButtonsAndExistingProjectsRemainProtected() {
        val checkout = "const buy = document.getElementById('checkoutBtn'); " +
            "if (buy) buy.addEventListener('click', function() { checkout(); });"
        assertTrue(runCatching { WorkspaceWebsiteVisualQuality.review(fresh, source(checkout)) }.isFailure)
        val existing = fresh.copy(original = source(observedGuardedScript).mapValues { it.value })
        val afterAnchor = WorkspaceWebsiteActionQuality.review(fresh, source(observedGuardedScript)).files
        assertTrue(runCatching { WorkspaceWebsiteScriptQuality.review(existing, afterAnchor) }.isFailure)
        val matching = source("const buy = document.getElementById('checkoutBtn'); " +
            "if (buy) buy.addEventListener('click', checkout);",
            originalHtml.replace("</body>", "<button id='checkoutBtn'>Buy</button></body>"))
        assertEquals(matching, WorkspaceWebsiteScriptQuality.review(fresh, matching))
    }

    @Test fun noNativeDestinationNeverDeletesGeneratedScript() {
        val input = source(observedGuardedScript)
        assertTrue(runCatching { WorkspaceWebsiteScriptQuality.review(fresh, input) }.isFailure)
        val alteredGoal = fresh.copy(goal = "Build a different website without Explore Minicoy")
        val afterAnchor = WorkspaceWebsiteActionQuality.review(fresh, input).files
        assertTrue(runCatching { WorkspaceWebsiteScriptQuality.review(alteredGoal, afterAnchor) }.isFailure)
    }
}
