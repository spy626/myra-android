package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteAnchorScriptQualityTest {
    private val goal = "Minicoy Island ki mobile-friendly website banao. Blue hero, white Welcome to Minicoy, " +
        "orange Explore Minicoy button, Beaches, Lighthouse, Local Food ke 3 pink cards. " +
        "Button tap karne par Things to Explore tak scroll ho aur Exploring Minicoy! dikhaye."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "approved", goal,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private val html = """<html><head><link rel="stylesheet" href="style.css"></head><body>
        <h1>Welcome to Minicoy</h1>
        <a href="#lyra-explore-section" class="cta-button">Explore Minicoy</a>
        <h2 class="lyra-explore-target" id="lyra-explore-section">Things to Explore</h2>
        <p class="lyra-explore-feedback">Exploring Minicoy!</p>
        <h3>Beaches</h3><h3>Lighthouse</h3><h3>Local Food</h3>
        <script src="script.js"></script></body></html>""".trimIndent()
    private val css = ".lyra-explore-target:target + .lyra-explore-feedback { display: block; }"
    // Reproduced from the saved script.js in the September 21 phone recording.
    private val observed = """
        // Smooth scroll for older browsers (optional)
        document.querySelectorAll('a[href^="#"]').forEach(anchor => {
            anchor.addEventListener('click', function (e) {
                e.preventDefault();
                const target = document.querySelector(this.getAttribute('href'));
                if (target) {
                    target.scrollIntoView({ behavior: 'smooth' });
                    // Optional focus for accessibility
                    target.setAttribute('tabindex', '-1');
                    target.focus();
                }
            });
        });
    """.trimIndent()
    private fun files(js: String = observed, markup: String = html, styles: String = css) =
        mapOf("index.html" to markup, "style.css" to styles, "script.js" to js)

    @Test fun recordedAnchorInterceptorIsRemovedAndNativeFeedbackCanActivate() {
        val reviewed = WorkspaceWebsiteConsistency.verify(fresh, files())
        assertFalse(reviewed.getValue("script.js").contains("preventDefault"))
        assertTrue(reviewed.getValue("script.js").contains("native in-page link"))
        assertTrue(reviewed.getValue("index.html").contains("href=\"#lyra-explore-section\""))
        assertEquals(reviewed, WorkspaceWebsiteConsistency.verify(fresh, reviewed))
    }

    @Test fun additionalEffectsAndUnrecognisedInterceptionFailClosed() {
        val extra = observed + "\nfetch('https://example.org/secret');"
        assertTrue(runCatching { WorkspaceWebsiteConsistency.verify(fresh, files(extra)) }.isFailure)
        val changedAction = observed.replace("target.focus();", "target.click();")
        assertTrue(runCatching { WorkspaceWebsiteConsistency.verify(fresh, files(changedAction)) }.isFailure)
        val unverified = files(markup = html.replace("id=\"lyra-explore-section\"", "id=\"other\""))
        assertTrue(runCatching { WorkspaceWebsiteAnchorScriptQuality.review(fresh, unverified) }.isFailure)
    }

    @Test fun existingProjectsAndUnrelatedScriptsAreNotRewritten() {
        val existing = fresh.copy(original = files().mapValues { it.value })
        assertEquals(files(), WorkspaceWebsiteAnchorScriptQuality.review(existing, files()))
        val unrelated = files("console.log('not navigation');")
        assertEquals(unrelated, WorkspaceWebsiteAnchorScriptQuality.review(fresh, unrelated))
        val otherGoal = fresh.copy(goal = "Build a portfolio website")
        assertEquals(files(), WorkspaceWebsiteAnchorScriptQuality.review(otherGoal, files()))
    }
}
