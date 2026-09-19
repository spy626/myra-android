package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCodeBlocksTest {
    @Test fun separatesIntroCodeAndNextStepWithoutChangingSource() {
        val raw = "Here is your button:\n```html\n<button id=\"go\">Click</button>\n```\nTap Preview."
        val parts = WorkspaceCodeBlocks.parse(raw)
        assertEquals(3, parts.size)
        assertEquals("Here is your button:", (parts[0] as WorkspaceCodeBlocks.Part.Prose).text)
        val code = parts[1] as WorkspaceCodeBlocks.Part.Code
        assertEquals("html", code.language)
        assertEquals("<button id=\"go\">Click</button>", code.source)
        assertEquals("Tap Preview.", (parts[2] as WorkspaceCodeBlocks.Part.Prose).text)
    }

    @Test fun preservesUnclosedFencesAsOrdinaryText() {
        val raw = "Intro\n```html\n<button>not finished"
        assertEquals(listOf(WorkspaceCodeBlocks.Part.Prose(raw)), WorkspaceCodeBlocks.parse(raw))
    }

    @Test fun supportsMultipleBlocksAndLongerFenceWithoutEatingLiteralBackticks() {
        val raw = "````js\nconst literal = '```';\n````\n```css\nbody { color: red; }\n```"
        val blocks = WorkspaceCodeBlocks.parse(raw).filterIsInstance<WorkspaceCodeBlocks.Part.Code>()
        assertEquals(2, blocks.size)
        assertEquals("const literal = '```';", blocks[0].source)
        assertEquals("body { color: red; }", blocks[1].source)
    }

    @Test fun htmlPreviewRequiresCompleteHtmlAndIsNeverAutomatic() {
        assertTrue(WorkspaceCodeBlocks.canPreview(WorkspaceCodeBlocks.Part.Code("html",
            "<!doctype html><html><button>Go</button></html>")))
        assertFalse(WorkspaceCodeBlocks.canPreview(WorkspaceCodeBlocks.Part.Code("html", "<button>Go</button>")))
        assertFalse(WorkspaceCodeBlocks.canPreview(WorkspaceCodeBlocks.Part.Code("js", "<html></html>")))
    }

    @Test fun codeWithoutFencesIsPreserved() {
        val original = "Use `button` to create a button."
        assertEquals(listOf(WorkspaceCodeBlocks.Part.Prose(original)), WorkspaceCodeBlocks.parse(original))
    }
}
