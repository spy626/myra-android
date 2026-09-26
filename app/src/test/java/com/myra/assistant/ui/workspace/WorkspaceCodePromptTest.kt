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
    }
}
