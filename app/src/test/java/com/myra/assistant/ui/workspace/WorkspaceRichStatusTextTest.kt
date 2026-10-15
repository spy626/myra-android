package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceRichStatusTextTest {
    @Test fun statusEmojiBecomeNeutralWordsInTableAndListCells() {
        assertEquals("Ho gaya", WorkspaceRichStatusText.neutralize("✅"))
        assertEquals("Ho gaya", WorkspaceRichStatusText.neutralize("🟢 Ho gaya"))
        assertEquals("Nahi hua: Payment", WorkspaceRichStatusText.neutralize("🔴: Payment"))
        assertEquals("Check karna hai Details", WorkspaceRichStatusText.neutralize("❓ Details"))
        assertEquals("Baaki", WorkspaceRichStatusText.neutralize("🟡"))
        assertEquals("Ho gaya Cart", WorkspaceRichStatusText.neutralize("✔️ Cart"))
    }

    @Test fun nonStatusContentAndAppNamesRemainIntact() {
        assertEquals("Chrome", WorkspaceRichStatusText.neutralize("Chrome"))
        assertEquals("Rice ₹65", WorkspaceRichStatusText.neutralize("Rice ₹65"))
        assertEquals("Home 🛒 Cart", WorkspaceRichStatusText.neutralize("Home 🛒 Cart"))
        assertEquals("Product 📱", WorkspaceRichStatusText.neutralize("Product 📱"))
    }

    @Test fun fullAndFreeContractsAskForPlainStatusWordsAndVisualVariety() {
        val full = WorkspaceRichBlocksContract.INSTRUCTIONS
        val compact = WorkspaceRichBlocksContract.COMPACT_GROQ_INSTRUCTIONS
        assertTrue(full.contains("NEUTRAL TEXT"))
        assertTrue(full.contains("NEVER use colored/status"))
        assertTrue(compact.contains("status = neutral text"))
        assertTrue(compact.contains("Baaki"))
        assertTrue(full.contains("No compulsory visual block"))
        assertTrue(compact.contains("A visual is OPTIONAL"))
        assertTrue(full.contains("mockup_card is optional ONLY"))
    }
}
