package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteVisualQualityTest {
    private val source = WorkspaceWebsiteGeneration.Snapshot("website", "task", "approved",
        "Add three exploration cards", mapOf("index.html" to null, "style.css" to null,
            "script.js" to null))
    private fun code(html: String) = mapOf("index.html" to html, "style.css" to "", "script.js" to "")

    @Test fun removedImageDoesNotLeaveEmptyWhiteMediaWrapper() {
        val page = "<html><head></head><body><section class='cards'>" +
            "<article class='card'><div class='card-image' style='height:240px'>" +
            "<img src='missing-beach.jpg' alt='Beach'></div><h2>Beaches</h2><p>Visit Minicoy.</p></article>" +
            "<article class='card'><div class='card-photo'><img src='bad-lighthouse.png'></div>" +
            "<h2>Lighthouse</h2></article></section></body></html>"
        val review = WorkspaceWebsiteVisualQuality.review(source, code(page))
        val html = review.files.getValue("index.html")
        assertEquals(2, review.removedImages)
        assertEquals(2, review.removedEmptyMedia)
        assertFalse(html.contains("card-image"))
        assertFalse(html.contains("card-photo"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("<h2>Beaches</h2>"))
        assertTrue(html.contains("<h2>Lighthouse</h2>"))
        assertTrue(html.contains("width=device-width"))
        assertTrue(review.chatNote().contains("Inspect Preview"))
    }

    @Test fun existingAssetMarkupAndOtherElementsRemainUntouched() {
        val original = "<html><head><meta name='viewport' content='width=device-width'></head>" +
            "<body><div class='card-image'><img src='existing.jpg'></div>" +
            "<div class='card-image'></div><div class='card'></div></body></html>"
        val snapshot = source.copy(original = source.original + ("index.html" to original))
        val review = WorkspaceWebsiteVisualQuality.review(snapshot, code(original))
        assertEquals(original, review.files.getValue("index.html"))
        assertEquals(0, review.removedImages)
        assertEquals(0, review.removedEmptyMedia)
        assertFalse(review.viewportAdded)
    }

    @Test fun newMediaSlotRemovedButLegitimateTextAndDecorativeContainersRemain() {
        val page = "<html><body><div class=\"beach-image\"></div>" +
            "<div class=\"image-label\">Beach details</div>" +
            "<div class=\"hero\"></div><figure class=\"photo-area\"></figure>" +
            "</body></html>"
        val review = WorkspaceWebsiteVisualQuality.review(source, code(page))
        val html = review.files.getValue("index.html")
        assertEquals(2, review.removedEmptyMedia)
        assertTrue(html.contains("Beach details"))
        assertTrue(html.contains("class=\"hero\""))
    }

    @Test fun decodesBoundedReadOnlyDomMetricsWithoutClaimingVisualSuccess() {
        val encoded = JSONObject.quote("""{"overflow":true,"brokenImages":2,"emptyMedia":1}""")
        val findings = requireNotNull(WorkspaceWebsiteVisualQuality.decodeDomResult(encoded))
        assertTrue(findings.overflow)
        assertTrue(findings.status().contains("horizontal overflow"))
        assertTrue(findings.status().contains("broken image"))
        val clear = requireNotNull(WorkspaceWebsiteVisualQuality.decodeDomResult(
            JSONObject.quote("""{"overflow":false,"brokenImages":0,"emptyMedia":0}""")))
        assertTrue(clear.status().contains("inspect appearance on phone"))
        assertNull(WorkspaceWebsiteVisualQuality.decodeDomResult("{}"))
        assertNull(WorkspaceWebsiteVisualQuality.decodeDomResult(JSONObject.quote("""{"overflow":true,"brokenImages":300,"emptyMedia":0}""")))
    }
}
