#!/usr/bin/env python3
"""One-shot fail-closed repair: never report unchanged existing website as a completed edit."""
from pathlib import Path

root = Path('app/src/main/java/com/myra/assistant/ui/workspace')

def replace_exact(path, original, updated):
    text = path.read_text(encoding='utf-8')
    assert text.count(original) == 1, f'Expected exactly one anchored match in {path}'
    path.write_text(text.replace(original, updated, 1), encoding='utf-8')

website = root / 'WorkspaceWebsiteGeneration.kt'
original = '            "On existing projects preserve the current palette, typography, sections and working UI " +\n            "unless the user explicitly asks to change them; avoid unrelated full-page redesigns. " +\n'
updated = ('            "When existingFiles contain a website, implement the LATEST user goal as a real " +\n'
           '            "change in the relevant HTML, CSS or JavaScript. Preserve unrelated working behavior. " +\n'
           '            "Never return all existingFiles unchanged for a change request: LYRA rejects " +\n'
           '            "unchanged results without saving. For a visual request, modify rendered styles " +\n'
           '            "or markup rather than only comments or a completion claim. " +\n' + original)
replace_exact(website, original, updated)
original = '    /** One Send grants this build\'s three project-local file writes. Persist rollback first. */\n'
updated = ('    /** A provider result is not a completed edit unless a saved project file actually changes.\n'
           '     * This check happens after guarded repairs and again before the first backup/write.\n'
           '     * It never invents a substitute edit, charges another request, or mutates the project.\n'
           '     */\n'
           '    internal fun requireChanged(snapshot: Snapshot, generated: Map<String, String>) {\n'
           '        require(generated.keys == PATHS.toSet()) {\n'
           '            "Website result is missing required files; no files changed"\n'
           '        }\n'
           '        require(PATHS.any { path -> snapshot.original[path] != generated.getValue(path) }) {\n'
           '            "Website model returned unchanged files; requested edit not applied. " +\n'
           '                "No files changed or automatic resend."\n'
           '        }\n'
           '    }\n\n' + original)
replace_exact(website, original, updated)
original = '        val file = backupFile(projects, snapshot.projectId)\n        val entries = JSONObject()\n'
updated = '        requireChanged(snapshot, generated)\n' + original
replace_exact(website, original, updated)

review = root / 'WorkspaceWebsiteVisualQuality.kt'
original = '        return SourceReview(files, removedImages, removedEmpty, viewportAdded,\n'
updated = '        WorkspaceWebsiteGeneration.requireChanged(snapshot, files)\n' + original
replace_exact(review, original, updated)

flow = root / 'WorkspaceChatCodingFlow.kt'
original = '                        error("Website layout safeguard rejected this output: ${issue.message}. No files changed.")\n'
updated = '                        error("Website result rejected: ${issue.message}. No files changed.")\n'
replace_exact(flow, original, updated)

# Dedicated regression cases: the same generic rule applies to visual and functional edits.
tests = Path('app/src/test/java/com/myra/assistant/ui/workspace/WorkspaceWebsiteNoOpEditTest.kt')
assert not tests.exists(), f'Do not overwrite {tests}'
tests.write_text('''package com.myra.assistant.ui.workspace

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
        val edited = old + ("script.js" to (js + "\\ndocument.addEventListener('keydown', () => {});"))
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
''', encoding='utf-8')

print('PATCHED: website request instruction, post-review no-op rejection, pre-write no-op defense, honest failure, four regression cases')
