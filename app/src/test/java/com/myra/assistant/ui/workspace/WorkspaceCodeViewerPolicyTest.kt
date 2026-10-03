package com.myra.assistant.ui.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCodeViewerPolicyTest {
    @Test fun longCodeRemainsVisibleAndTappingCardOpensViewerWithoutFooter() {
        val card = File("src/main/java/com/myra/assistant/ui/workspace/WorkspaceCodeCardView.kt").readText()
        assertTrue(card.contains("card.addView(horizontal, LinearLayout.LayoutParams(-1, -2))"))
        assertTrue(card.contains("setOnClickListener { openCode() }"))
        assertFalse(card.contains("Open full screen ↗"))
        assertFalse(card.contains("unit(310)"))
        assertFalse(card.contains("needsCompactCard"))
    }

    @Test fun previewTapOpensFullScreenInsteadOfEmbeddingWebViewInChat() {
        val card = File("src/main/java/com/myra/assistant/ui/workspace/WorkspaceCodeCardView.kt").readText()
        assertTrue(card.contains("WorkspaceCodeViewer.open(context, block, true, onCopy)"))
        assertTrue(card.contains("WorkspaceCodeViewer.open(context, block, false, onCopy)"))
        assertFalse(card.contains("offlineHtml(context, block.source)"))
        assertFalse(card.contains("text = \"Copy\""))
        val viewer = File("src/main/java/com/myra/assistant/ui/workspace/WorkspaceCodeViewer.kt").readText()
        assertTrue(viewer.contains("viewport.addView(page, FrameLayout.LayoutParams(-1, -1))"))
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
