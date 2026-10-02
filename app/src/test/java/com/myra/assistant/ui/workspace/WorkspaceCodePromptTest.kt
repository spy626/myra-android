package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCodePromptTest {
    @Test fun codeFormattingIsRequestedOnlyForCodeRelatedPrompts() {
        assertTrue(WorkspaceCodePrompt.instructions("Hi LYRA").isEmpty())
        val rules = WorkspaceCodePrompt.instructions("Ek simple HTML button banao, code bhi do")
        assertTrue(rules.contains("comments in English"))
        assertTrue(rules.contains("valid /* CSS comments */"))
        assertTrue(rules.contains("standalone HTML"))
        listOf(
            "Bro sirf phone hai, 3 planning steps batao, abhi coding start mat karna",
            "I only have a phone; give 3 first steps, don't code yet.",
            "Plan a free website in 3 steps, no implementation yet."
        ).forEach { text ->
            assertTrue(text, WorkspaceCodePrompt.instructions(text).isBlank())
        }
        assertTrue(WorkspaceCodePrompt.instructions(
            "Create an HTML page and give me its code now"
        ).contains("Code-answer formatting"))
    }
}
