package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePlanningAnswerBoundaryTest {
    private val phonePlan = "bro mere paas sirf Android phone hai aur mujhe free mein " +
        "ek simple grocery app banana hai. Sabse pehle kya karna chahiye? " +
        "3 practical steps batao, abhi coding start mat karna 😂"

    @Test fun latestScreenshotImplementationPopupCannotBeTriggeredByProseHeuristics() {
        // The rejected provider completion is not available in the screenshot.
        // Even ambiguous planning vocabulary must be DISPLAYED, not discarded
        // based on a fragile guess of implementation stage.
        val examples = listOf(
            """
                1. **Plan:** Home aur product screen create karne ka rough idea likho.
                2. **Design:** Cart layout ka sketch Notes par banao.
                3. **Content:** Sample data aur screen blocks ki list banao.
            """.trimIndent(),
            """
                Step 1: Features decide karo.
                Step 2: Future mein visual builder mein screens connect ho sakti hain.
                Step 3: Five grocery item prices likho.
            """.trimIndent(),
            """
                Pehle Home, Product aur Cart ka flow banao.
                Paper par New Project ka conceptual plan likho, coding mat karna.
            """.trimIndent(),
            """
                Install a free visual builder and create a New Project.
                Connect the screen blocks after the project exists.
            """.trimIndent()
        )
        examples.forEach { reply ->
            assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, reply))
            assertEquals(reply, WorkspacePlanningAnswerBoundary.requireAcceptable(phonePlan, reply))
        }
    }

    @Test fun explicitSourceCodeFenceStillViolatesNoCodingInstruction() {
        val code = "```kotlin\nfun main() = println(\"hi\")\n```"
        assertTrue(WorkspacePlanningAnswerBoundary.violation(phonePlan, code)
            .orEmpty().contains("source-code"))
        assertTrue(runCatching {
            WorkspacePlanningAnswerBoundary.requireAcceptable(phonePlan, code)
        }.isFailure)
        assertNull(WorkspacePlanningAnswerBoundary.violation(
            "Build the grocery app now and give me Kotlin code", code
        ))
    }

    @Test fun quotedWordsTablesNegativeWarningsAndLaterNotesAreNotFalsePositives() {
        val reply = """
            ## 3 practical steps
            1. Scope likho: Home, Product aur Cart.
            2. Rough screens sketch karo (no builder install).
            3. Sample prices Notes par list karo.

            | Stage | Meaning |
            | --- | --- |
            | Plan | Create a screen sketch |
            | Later | Install a visual builder |

            **Later:** Actual project create karna implementation hai, abhi mat karo.
        """.trimIndent()
        assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, reply))
        assertEquals(reply, WorkspacePlanningAnswerBoundary.requireAcceptable(phonePlan, reply))
        assertFalse(WorkspacePracticalPlanningGuide.instructions(phonePlan).isBlank())
        assertTrue(WorkspacePracticalPlanningGuide.instructions(phonePlan)
            .contains("SETUP and IMPLEMENTATION are NOT planning"))
    }

    @Test fun markerFormattingAndLengthNeverCauseCompletedReplyRejection() {
        listOf(
            "### Step 1: Features\n### Step 2: Sketch\n### Step 3: Sample data",
            "**Step 1:** Features\n**Step 2:** Sketch\n**Step 3:** Prices",
            "Features, design aur prices ka plan Notes par likho.",
            "1. Features\n2. Sketch",
            "1. Features\n2. Sketch\n3. Prices\n4. Optional later"
        ).forEach { reply ->
            assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, reply))
        }
    }

    @Test fun casualWritingAndExplicitExecutionAreUnchanged() {
        val text = "Install an app builder and create a New Project."
        assertNull(WorkspacePlanningAnswerBoundary.violation("Hi bro!", text))
        assertNull(WorkspacePlanningAnswerBoundary.violation(
            "Now implement my project; start actual coding", text))
    }
}
