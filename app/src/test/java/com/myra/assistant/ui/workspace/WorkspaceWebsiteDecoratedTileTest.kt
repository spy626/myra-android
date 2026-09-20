package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteDecoratedTileTest {
    private val snapshot = WorkspaceWebsiteGeneration.Snapshot(
        "site", "task", "token", "Build mobile cards",
        mapOf("index.html" to null, "style.css" to null, "script.js" to null))

    @Test fun cssGradientArtworkSurvivesButPlainBlankMediaIsRemoved() {
        val html = "<html><head></head><body>" +
            "<article class='card'><div class='card-image'></div><h2>Beach</h2></article>" +
            "<article class='card'><div class='photo-area'></div><h2>Food</h2></article>" +
            "</body></html>"
        val result = WorkspaceWebsiteVisualQuality.review(snapshot, mapOf(
            "index.html" to html,
            "style.css" to ".card-image { background: linear-gradient(90deg, #e2f0e4, #a0bebe); }",
            "script.js" to ""))
        assertTrue(result.files.getValue("index.html").contains("class='card-image'"))
        assertFalse(result.files.getValue("index.html").contains("class='photo-area'"))
        assertTrue(result.files.getValue("index.html").contains("<h2>Beach</h2>"))
        assertTrue(result.files.getValue("index.html").contains("<h2>Food</h2>"))
        assertEquals(1, result.removedEmptyMedia)
    }

    @Test fun inlineGradientArtworkIsNotMistakenForMissingPhoto() {
        val html = "<html><body><div class='hero-photo' style='background:radial-gradient(circle, #fff, #444)'></div>" +
            "<div class='hero-image' style='height:300px'></div></body></html>"
        val result = WorkspaceWebsiteVisualQuality.review(snapshot, mapOf(
            "index.html" to html, "style.css" to "", "script.js" to ""))
        assertTrue(result.files.getValue("index.html").contains("hero-photo"))
        assertFalse(result.files.getValue("index.html").contains("hero-image"))
        assertEquals(1, result.removedEmptyMedia)
    }
}
