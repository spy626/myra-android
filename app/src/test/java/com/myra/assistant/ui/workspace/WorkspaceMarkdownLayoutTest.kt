package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceMarkdownLayoutTest {
    private fun lines(raw: String) = WorkspaceMarkdownLayout.prepare(raw)

    @Test fun headingsAndNumberedStepsHaveSeparationWhileBulletRowsStayCompact() {
        val result = lines("Intro\n## Plan\n1. **Define** the flow\n2. **Sketch** screens\n- Home\n- Cart")
        assertEquals(listOf(
            "Intro", "", "## Plan", "", "1. **Define** the flow", "",
            "2. **Sketch** screens", "• Home", "• Cart"
        ), result.map { it.text })
        assertEquals(WorkspaceMarkdownLayout.Kind.NUMBERED, result[4].kind)
        assertEquals(WorkspaceMarkdownLayout.Kind.BULLET, result[7].kind)
    }

    @Test fun twoColumnMarkdownTableBecomesReadableStackedRowsOnPhone() {
        val result = lines("| Tool | Purpose |\n| --- | :--- |\n| Notes | Outline first |\n| SPCK | Code later |")
        assertEquals(listOf(
            "Tool · Purpose",
            "**Notes** — **Purpose:** Outline first",
            "**SPCK** — **Purpose:** Code later"
        ), result.map { it.text })
        assertEquals(WorkspaceMarkdownLayout.Kind.TABLE_TITLE, result[0].kind)
        assertEquals(WorkspaceMarkdownLayout.Kind.TABLE_ROW, result[1].kind)
        assertFalse(result.joinToString("\n") { it.text }.contains("| --- |"))
    }

    @Test fun plainConversationCodePillAndDirectApkLinkRemainUntouched() {
        val url = "[⬇ APK](https://github.com/spy626/myra-android/releases/download/" +
            "airi-memory-ce4098793ff6/lyra-phone-test.apk)"
        val raw = "Hello bro 😂\nUse `main`.\n" + url
        assertEquals(raw, lines(raw).joinToString("\n") { it.text })
        assertEquals(1, WorkspaceVerifiedChatLinks.find(lines(raw).last().text).size)
    }

    @Test fun fencedLiteralMarkdownAndNonTablePipesRemainUnchanged() {
        val raw = "Story | idea\n```markdown\n- literal\n| a | b |\n| --- | --- |\n```\nEnd"
        assertEquals(raw, lines(raw).joinToString("\n") { it.text })
        assertEquals(WorkspaceMarkdownLayout.Kind.PLAIN, lines("Ordinary sentence").single().kind)
    }

    @Test fun threeColumnRowsPreserveColumnMeaning() {
        val result = lines("| App | Now | Later |\n| --- | --- | --- |\n| Example | Outline | Build |")
        assertTrue(result.last().text.contains("**Now:** Outline"))
        assertTrue(result.last().text.contains("**Later:** Build"))
    }
}
