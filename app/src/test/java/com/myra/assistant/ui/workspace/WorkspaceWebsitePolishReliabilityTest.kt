package com.myra.assistant.ui.workspace

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsitePolishReliabilityTest {
    private val brief = "Minicoy Island ki mobile-friendly website banao. Blue hero section, " +
        "white Welcome to Minicoy heading, orange Explore Minicoy button aur Beaches, " +
        "Lighthouse, Local Food ke 3 compact pink cards rakho. Button tap karne par " +
        "Things to Explore section tak scroll ho aur Exploring Minicoy! text clearly dikhaye."
    private val fresh = WorkspaceWebsiteGeneration.Snapshot("site", "task", "approved", brief,
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))
    private val page = """<html><head><link rel="stylesheet" href="style.css"></head><body>
        <header class="hero"><h1>Welcome to Minicoy</h1><a href="#things">Explore Minicoy</a></header>
        <section class="cards-section"><h2>Things to Explore</h2><div class="lyra-requested-explore-cards">
        <article class="lyra-requested-explore-card"><h3>Beaches</h3><p>Explore coastal scenery.</p></article>
        <article class="lyra-requested-explore-card"><h3>Lighthouse</h3><p>Learn about an island landmark.</p></article>
        <article class="lyra-requested-explore-card"><h3>Local Food</h3><p>Discover island flavours.</p></article>
        </div></section><script src="script.js"></script></body></html>""".trimIndent()
    private fun output(html: String = page) = mapOf(
        "index.html" to html, "style.css" to ".hero{background:teal}", "script.js" to "")

    @Test fun freshTextRequestAndJsonCompatibilityRetainSameApprovedSource() {
        val firstRequest = WorkspaceWebsiteGroqFallback.request("gsk_test_key", fresh)
        val compatible = WorkspaceWebsiteGroqFallback.compatibilityRequest("gsk_test_key", fresh)
        fun body(request: okhttp3.Request): JSONObject {
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            return JSONObject(buffer.readUtf8())
        }
        val first = body(firstRequest)
        val second = body(compatible)
        assertFalse(first.has("response_format"))
        assertEquals("json_object", second.getJSONObject("response_format").getString("type"))
        assertEquals(first.getJSONArray("messages").toString(), second.getJSONArray("messages").toString())
        assertEquals(first.getString("model"), second.getString("model"))
        assertEquals(first.getInt("max_completion_tokens"), second.getInt("max_completion_tokens"))
        assertFalse(second.has("provider"))
        assertFalse(second.has("plugins"))
        assertFalse(second.has("reasoning_format"))
        assertFalse(second.getBoolean("include_reasoning"))
        assertEquals(WorkspaceGroqFree.ENDPOINT, compatible.url.toString())
        assertFalse(second.toString().contains("gsk_test_key"))
        assertEquals(3, WorkspaceWebsiteGroqFallback.MAX_WEBSITE_ATTEMPTS)
        assertFalse(WorkspaceWebsiteGroqFallback.recoverAfterFallbackGroq(400, 3))
        assertFalse(WorkspaceWebsiteGroqFallback.recoverAfterFallbackGroq(429, 2))
        val existing = fresh.copy(original = output().mapValues { it.value })
        val existingRequest = body(WorkspaceWebsiteGroqFallback.request("gsk_test_key", existing))
        assertEquals("json_schema", existingRequest.getJSONObject("response_format").getString("type"))
    }

    @Test fun exactFallbackCopyGetsSpecificTextAndStyledCtaWithoutDuplicateFeedback() {
        val first = WorkspaceWebsiteVisualQuality.review(fresh, output())
        val html = first.files.getValue("index.html")
        val css = first.files.getValue("style.css")
        assertTrue(first.polishedDesign)
        assertTrue(html.contains("Minicoy's lagoon-side shores"))
        assertTrue(html.contains("Minicoy's lighthouse"))
        assertTrue(html.contains("coconut and seafood"))
        assertFalse(html.contains("Explore coastal scenery."))
        assertFalse(html.contains("Learn about an island landmark."))
        assertFalse(html.contains("Discover island flavours."))
        assertEquals(1, Regex("Exploring Minicoy!").findAll(html).count())
        assertTrue(css.contains("text-decoration: none !important"))
        assertTrue(css.contains("background: #ffb454 !important"))
        assertTrue(css.contains("grid-template-columns: minmax(0, 1fr) !important"))
        assertEquals(first.files, WorkspaceWebsiteConsistency.verify(fresh, first.files))
        assertEquals(first.files, WorkspaceWebsiteVisualQuality.review(fresh, first.files).files)
    }

    @Test fun authoredContentAndExistingProjectsStayUntouched() {
        val custom = output(page.replace("Explore coastal scenery.", "My own beach guide."))
        val polished = WorkspaceWebsiteDesignPolish.review(fresh, custom)
        assertTrue(polished.files.getValue("index.html").contains("My own beach guide."))
        assertFalse(polished.files.getValue("index.html").contains("Minicoy's lagoon-side shores"))
        val existing = fresh.copy(original = custom.mapValues { it.value })
        assertEquals(custom, WorkspaceWebsiteDesignPolish.review(existing, custom).files)
        assertFalse(WorkspaceWebsiteDesignPolish.review(existing, custom).changed)
        val unrelated = fresh.copy(goal = "Build a simple blue portfolio with pink cards")
        assertEquals(custom, WorkspaceWebsiteDesignPolish.review(unrelated, custom).files)
    }

    @Test fun incompleteJsonStillCannotPassAsSavedFiles() {
        assertTrue(runCatching {
            WorkspaceWebsiteGeneration.parse("{\"files\":{\"index.html\":\"<html>\"")
        }.isFailure)
    }
}
