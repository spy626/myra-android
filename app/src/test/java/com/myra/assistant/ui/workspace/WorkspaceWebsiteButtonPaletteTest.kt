package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteButtonPaletteTest {
    private val html = """<!doctype html><html><head><link rel="stylesheet" href="style.css"></head><body><button id="minus" class="counter">Minus</button><button id="plus" class="counter">Plus</button><button id="reset" class="counter">Reset</button><script src="script.js"></script></body></html>"""
    private val js = "document.querySelectorAll('button').forEach(b => b.addEventListener('click', () => {}));"
    private val darkAndAmber = "body { background: #191919 } .counter { background: #202020; color: white } #reset { background: #d97706; }"
    private fun snapshot(goal: String, css: String): WorkspaceWebsiteGeneration.Snapshot =
        WorkspaceWebsiteGeneration.Snapshot("project", "task", "spec", goal,
            mapOf("index.html" to html, "style.css" to css, "script.js" to js))
    private fun website(css: String) = mapOf("index.html" to html, "style.css" to css, "script.js" to js)

    @Test fun conflictingButtonBackgroundsRejectAnExplicitPaletteRequest() {
        val original = snapshot("Change to charcoal theme with amber buttons. Keep actions working.", "body{background:white}")
        val error = runCatching { WorkspaceWebsiteButtonPalette.verify(original, website(darkAndAmber)) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("every button"))
    }

    @Test fun allButtonColorsFromOneRuleCanPassWithoutChangingJavaScript() {
        val original = snapshot("Give the controls amber buttons", "body{background:white}")
        val updated = website("body { background: #191919 } .counter { background: #d97706; color: white; }")
        WorkspaceWebsiteButtonPalette.verify(original, updated)
        assertEquals(js, updated["script.js"])
    }

    @Test fun variableAndRgbRulesWorkForDifferentRequestedColors() {
        val original = snapshot("Set blue buttons", "body{background:white}")
        WorkspaceWebsiteButtonPalette.verify(original,
            website(":root{--action: rgb(37,99,235);} .counter{background: var(--action);}"))
        val green = snapshot("Use green buttons", "body{background:white}")
        WorkspaceWebsiteButtonPalette.verify(green, website("button{background:#16a34a}"))
    }

    @Test fun ordinaryFunctionEditsAreNotConstrainedByPaletteChecks() {
        val original = snapshot("Make Plus, Minus and Reset work with keyboard", "body{background:white}")
        WorkspaceWebsiteButtonPalette.verify(original, website(darkAndAmber))
    }

    @Test fun sourceReviewRejectsMixedPaletteBeforeAnyFilesAreSaved() {
        val original = snapshot("Change to dark background with amber buttons", "body{background:white}")
        val result = runCatching { WorkspaceWebsiteVisualQuality.review(original, website(darkAndAmber)) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("button color"))
    }
}
