package com.myra.assistant.ui.workspace

import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWebsiteNoOpEditTest {
    private val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="style.css"></head><body><h1>Counter</h1><button id="plus">Plus</button><script src="script.js"></script></body></html>"""
    private val css = "body { background: #fff5e6; color: #111; }"
    private val js = "document.getElementById('plus').addEventListener('click', () => {});"
    private val old = mapOf("index.html" to html, "style.css" to css, "script.js" to js)

    private fun snapshot(goal: String) = WorkspaceWebsiteGeneration.Snapshot(
        "website", "task", "spec", goal, old)

    @Test fun unchangedExistingWebsiteIsNotACompletedEditForDifferentGoals() {
        listOf("Change the current website appearance while keeping buttons working",
            "Add a keyboard shortcut to the existing website").forEach { goal ->
            val prior = snapshot(goal)
            val failure = runCatching { WorkspaceWebsiteVisualQuality.review(prior, old) }
                .exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("unchanged files"))
            assertTrue(failure.message.orEmpty().contains("No files changed"))
        }
    }

    @Test fun genuineCssChangePassesReviewAndOtherFilesRemainIntact() {
        val prior = snapshot("Change the current website appearance while keeping buttons working")
        val edited = old + ("style.css" to "body { background: #171717; color: #f4f4f4; }")
        val result = WorkspaceWebsiteVisualQuality.review(prior, edited).files
        assertEquals(edited, result)
        WorkspaceWebsiteGeneration.requireChanged(prior, result)
    }

    @Test fun functionalEditWithUnchangedCssIsAllowed() {
        val prior = snapshot("Add a keyboard shortcut to the existing website")
        val edited = old + ("script.js" to (js + "\ndocument.addEventListener('keydown', () => {});"))
        WorkspaceWebsiteGeneration.requireChanged(prior, edited)
        assertEquals(edited, WorkspaceWebsiteVisualQuality.review(prior, edited).files)
    }

    @Test fun providerInstructionsRequireRealChangeWithoutDiscardingExistingBehavior() {
        val request = WorkspaceWebsiteGeneration.request("fake_free_test_key", snapshot("Update existing page"))
        val bytes = Buffer()
        requireNotNull(request.body).writeTo(bytes)
        val instructions = JSONObject(bytes.readUtf8()).getJSONArray("messages")
            .getJSONObject(0).getString("content")
        assertTrue(instructions.contains("LATEST user goal"))
        assertTrue(instructions.contains("Preserve unrelated working behavior"))
        assertTrue(instructions.contains("unchanged results without saving"))
    }
}
