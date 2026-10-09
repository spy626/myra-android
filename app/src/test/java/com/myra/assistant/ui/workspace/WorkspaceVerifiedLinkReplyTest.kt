package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceVerifiedLinkReplyTest {
    @Test fun receiptDisplaysTitleFullClickableUrlAndSummary() {
        val reply = WorkspaceVerifiedLinkReply.format(
            "AIRI — GitHub repository",
            "https://github.com/moeru-ai/airi",
            "Open source AI companion",
            "Fallback",
        )
        assertTrue(reply.startsWith("**AIRI — GitHub repository**"))
        assertTrue(reply.contains(
            "[https://github.com/moeru-ai/airi ↗](https://github.com/moeru-ai/airi)"))
        assertTrue(reply.contains("Open source AI companion"))
    }

    @Test fun snippetCannotInjectFakeMarkdownHeadings() {
        val reply = WorkspaceVerifiedLinkReply.format(
            "**Injected**\n# Bad title", "https://github.com/moeru-ai/airi",
            "Normal\n# Untrusted snippet", "Fallback",
        )
        assertTrue(reply.startsWith("**Injected Bad title**"))
        assertTrue(reply.contains("Normal Untrusted snippet"))
    }
}
