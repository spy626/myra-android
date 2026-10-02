package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRichAnswerBlocksTest {
    @Test fun contextRelevantPlanningSectionsAreDistinctNativeBlocks() {
        val reply = """
            Phone se pehle scope define karo.
            
            ## 3 starting steps
            1. **Features likho** — customer product, cart, order.
            2. **Screens socho** — Home, Details, Cart, Checkout.
            3. **Route choose karo** — reason explain karo.
            
            ### Screens
            - Home: categories
            - Product detail: price
            - Cart: total
            
            | Tool | Purpose |
            | --- | --- |
            | Notes | Plan now |
            | Code editor | Build later |
        """.trimIndent()
        val blocks = WorkspaceRichAnswerBlocks.parse(reply)
        assertTrue(blocks.first() is WorkspaceRichAnswerBlocks.Block.Paragraph)
        assertTrue(blocks[1] is WorkspaceRichAnswerBlocks.Block.Heading)
        val steps = blocks.filterIsInstance<WorkspaceRichAnswerBlocks.Block.Numbered>().single().items
        assertEquals(3, steps.size)
        assertEquals("2", steps[1].number)
        assertEquals(3, blocks.filterIsInstance<WorkspaceRichAnswerBlocks.Block.Bullets>().single().items.size)
        val table = blocks.filterIsInstance<WorkspaceRichAnswerBlocks.Block.Table>().single()
        assertEquals(listOf("Tool", "Purpose"), table.headers)
        assertEquals("Plan now", table.rows[0][1])
        assertTrue(WorkspaceRichAnswerBlocks.isStructured(reply))
    }

    @Test fun smallTalkAndNaturalExplanationsDoNotGetForcedIntoCards() {
        listOf("Haan bro, ye interesting hai 😂", "Kal dekhenge. Abhi rest karo.", 
            "An ordinary sentence\ncontinuing on the next line.").forEach {
            assertFalse(WorkspaceRichAnswerBlocks.isStructured(it))
            assertEquals(1, WorkspaceRichAnswerBlocks.parse(it).size)
        }
    }

    @Test fun topicAgnosticBulletsWithNestedDepthAreReadCorrectly() {
        val b = WorkspaceRichAnswerBlocks.parse(
            "## Practice\n- Warm up\n  - Five minutes\n- Repeat chords"
        ).filterIsInstance<WorkspaceRichAnswerBlocks.Block.Bullets>().single()
        assertEquals(listOf(0, 1, 0), b.items.map { it.depth })
        assertEquals("Five minutes", b.items[1].text)
    }

    @Test fun malformedPipesStayProseAndQuotesAndRulesAreDistinct() {
        val blocks = WorkspaceRichAnswerBlocks.parse(
            "a | b\nnot a separator\n\n> A real quote\n\n---\nDone"
        )
        assertTrue(blocks[0] is WorkspaceRichAnswerBlocks.Block.Paragraph)
        assertTrue(blocks[1] is WorkspaceRichAnswerBlocks.Block.Quote)
        assertTrue(blocks[2] is WorkspaceRichAnswerBlocks.Block.Divider)
    }

    @Test fun verifiedBuildLinkRemainsByteForByteInDisplayData() {
        val raw = "## APK\n[Open Build](https://github.com/spy626/myra-android/actions/runs/36989540815)"
        val blocks = WorkspaceRichAnswerBlocks.parse(raw)
        val linkText = (blocks[1] as WorkspaceRichAnswerBlocks.Block.Paragraph).text
        assertEquals(raw.substringAfter('\n'), linkText)
        assertEquals(1, WorkspaceVerifiedChatLinks.find(linkText).size)
    }

    @Test fun inlineConsecutiveStepsFromWeakProviderBecomeIndividualRowsWithoutRewriting() {
        val raw = "Start small. 1. **Define:** list needs. 2. **Sketch:** screens. 3. **Decide:** route."
        val parsed = WorkspaceRichAnswerBlocks.parse(raw)
        val numbered = parsed.filterIsInstance<WorkspaceRichAnswerBlocks.Block.Numbered>().single()
        assertEquals(3, numbered.items.size)
        assertTrue(numbered.items[2].text.contains("**Decide:** route"))
        val ordinary = "My version is 1. 2 and price is 3. 4 rupees."
        assertFalse(WorkspaceRichAnswerBlocks.isStructured(ordinary))
    }

    @Test fun oversizedResponsesNeverExplodeIntoThousandsOfViews() {
        val value = ("- item\n").repeat(5000)
        assertEquals(1, WorkspaceRichAnswerBlocks.parse(value).size)
        assertFalse(WorkspaceRichAnswerBlocks.isStructured(value))
    }
}
