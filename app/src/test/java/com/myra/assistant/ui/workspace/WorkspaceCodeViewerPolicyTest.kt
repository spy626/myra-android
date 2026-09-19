package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCodeViewerPolicyTest {
    @Test fun longCodeIsCompactButShortCodeStaysNaturalHeight() {
        assertFalse(WorkspaceCodeViewerPolicy.needsCompactCard("one\ntwo\nthree"))
        assertFalse(WorkspaceCodeViewerPolicy.needsCompactCard((1..14).joinToString("\n")))
        assertTrue(WorkspaceCodeViewerPolicy.needsCompactCard((1..15).joinToString("\n")))
    }

    @Test fun onlyCompleteHtmlCanOpenPreviewAndNeverByDefault() {
        val html = WorkspaceCodeBlocks.Part.Code("html", "<!doctype html><html><body>Hi</body></html>")
        val partial = WorkspaceCodeBlocks.Part.Code("html", "<button>Hi</button>")
        val js = WorkspaceCodeBlocks.Part.Code("js", "document.write('Hi')")
        assertFalse(WorkspaceCodeViewerPolicy.initialPreview(html, false))
        assertTrue(WorkspaceCodeViewerPolicy.initialPreview(html, true))
        assertFalse(WorkspaceCodeViewerPolicy.initialPreview(partial, true))
        assertFalse(WorkspaceCodeViewerPolicy.initialPreview(js, true))
    }

    @Test fun copySourcePreservesSourceWithoutExplanationOrFence() {
        val parsed = WorkspaceCodeBlocks.parse("An explanation\n```html\n<button>Hi</button>\n```\nA next step")
        val code = parsed.filterIsInstance<WorkspaceCodeBlocks.Part.Code>().single()
        assertEquals("<button>Hi</button>", WorkspaceCodeViewerPolicy.copiedSource(code))
    }
}
