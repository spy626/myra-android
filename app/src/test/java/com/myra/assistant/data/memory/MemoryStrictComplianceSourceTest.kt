package com.myra.assistant.data.memory

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStrictComplianceSourceTest {
    private val sourceRoot = File("src/main")

    @Test fun memoryCoreUsesDrawableIconsInsteadOfDecorativeUnicodeGlyphs() {
        val source = File(sourceRoot, "java/com/myra/assistant/ui/settings/MemorySettingsActivity.kt").readText()
        assertTrue(source.contains("ImageView"))
        assertTrue(source.contains("categoryIconResource"))
        listOf("✦", "♙", "♡", "▣", "◎", "↻", "◇", "▤", "◉", "✧").forEach {
            assertFalse(source.contains("\"$it\""))
        }
    }

    @Test fun serviceDelegatesCorrectionSemanticsToMemoryBrain() {
        val source = File(sourceRoot, "java/com/myra/assistant/service/MyraVoiceService.kt").readText()
        assertFalse(source.contains("BestFriendNameCorrectionParser.analyze("))
        assertFalse(source.contains("BestFriendNameCorrectionParser.validateNewName("))
        assertFalse(source.contains("ClarifiedPersonNameResolver.resolve("))
        assertTrue(source.contains("memoryBrain.assessFinalTurn("))
        assertTrue(source.contains("memoryBrain.processPersonRename("))
    }
}
