package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePlanningAnswerBoundaryTest {
    private val phonePlan = "Bro mere paas sirf Android phone hai aur free mein ek " +
        "simple grocery app chahiye. Pehle 3 practical steps batao; abhi coding start mat karna."

    @Test fun actualObservedDesktopAndXmlInstructionsAreRejected() {
        val reply = """
            Start with a native Android project.
            1. **UI:** Screens define karo.
            2. Install Android Studio (Windows/Mac/Linux), setup Android SDK and emulator.
            3. Create activity_main.xml and activity_cart.xml files.
        """.trimIndent()
        val why = WorkspacePlanningAnswerBoundary.violation(phonePlan, reply)
        assertNotNull(why)
        assertTrue(why.orEmpty().contains("desktop") ||
            why.orEmpty().contains("implementation") ||
            why.orEmpty().contains("setup"))
        assertTrue(runCatching {
            WorkspacePlanningAnswerBoundary.requireAcceptable(phonePlan, reply)
        }.isFailure)
    }

    @Test fun codeFenceIsBlockedForAdviceOnlyButNotForExplicitCoding() {
        val prose = "Pehle ye code likho:\n" +
            "```kotlin\nfun main() = println(\"hi\")\n```"
        assertTrue(WorkspacePlanningAnswerBoundary.violation(phonePlan, prose)
            .orEmpty().contains("code"))
        assertNull(WorkspacePlanningAnswerBoundary.violation(
            "Build a grocery app now and give me its Kotlin code", prose))
    }

    @Test fun goodPhoneFirstPlansAndNegativeWarningsAreNotRejected() {
        val reply = """
            Phone aur zero budget ko dekhte hue pehle customer flow plan karo.

            ## First 3 steps
            1. **Features likho** — Home, Product, Cart, Checkout.
              - Home par categories
              - Cart par total
            2. **Screen sketch karo** — Notes ya paper par simple drawing.
            3. **Sample data likho** — 5 items, price aur category.

            **Later:** Android Studio requires a desktop; don't install it now.
        """.trimIndent()
        assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, reply))
        assertEquals(reply, WorkspacePlanningAnswerBoundary.requireAcceptable(phonePlan, reply))
        assertNull(WorkspacePlanningAnswerBoundary.violation("Hi bro!", "Install Android Studio"))
    }

    @Test fun oneConsolidatedGuideOwnsConstraintsNotSecondValidatorPrompt() {
        val guide = WorkspacePracticalPlanningGuide.instructions(phonePlan)
        assertTrue(guide.contains("EXACT MAIN STEP COUNT: give exactly 3"))
        assertTrue(guide.contains("Phone-only access"))
        assertTrue(guide.contains("Zero-budget"))
        assertTrue(guide.contains("PLANNING-ONLY HARD STOP"))
        assertTrue(guide.contains("Explicit target: UNSPECIFIED"))
        assertTrue(guide.contains("Roman Hinglish"))
        assertFalse(guide.contains("Sketchware"))
        assertFalse(guide.contains("grocery"))
        assertEquals("", WorkspacePracticalPlanningGuide.instructions("hi bro"))
    }

    @Test fun visualNoCodeBuilderFailureFromNewPhoneVideoIsAnImplementationViolation() {
        val raw = """
            Pehle simple customer flow socho.
            1. **Flow:** Notes mein Home, Product aur Cart likho.
            2. **Setup:** Sketchware install karo aur New Project banao.
            3. **Actual UI:** Screens create karo aur Open Screen blocks connect karo.
        """.trimIndent()
        val violation = WorkspacePlanningAnswerBoundary.violation(phonePlan, raw)
        assertNotNull(violation)
        assertTrue(violation.orEmpty().contains("setup"))
    }

    @Test fun classificationIsByActionStageNotBuilderBrand() {
        val b = WorkspacePlanningAnswerBoundary
        assertEquals(null, b.directiveStage("Rough screen sketch Notes mein banao"))
        assertEquals(null, b.directiveStage("Sample data aur features ki list likho"))
        assertEquals(WorkspacePlanningAnswerBoundary.Stage.SETUP, b.directiveStage("Install a free visual builder"))
        assertEquals(WorkspacePlanningAnswerBoundary.Stage.SETUP, b.directiveStage("Naya Project banao"))
        assertEquals(WorkspacePlanningAnswerBoundary.Stage.SETUP, b.directiveStage("Open an app builder and create a project"))
        assertEquals(WorkspacePlanningAnswerBoundary.Stage.IMPLEMENTATION,
            b.directiveStage("Actual screens create karo aur blocks connect karo"))
        assertEquals(WorkspacePlanningAnswerBoundary.Stage.IMPLEMENTATION,
            b.directiveStage("Visual components drag karo, events wire karo"))
        assertEquals(WorkspacePlanningAnswerBoundary.Stage.IMPLEMENTATION,
            b.directiveStage("Start building a working grocery app"))
        assertEquals(WorkspacePlanningAnswerBoundary.Stage.IMPLEMENTATION,
            b.directiveStage("Rough idea note karo; phir actual screens create karo"))
    }

    @Test fun negativeInstructionAfterEarlierSetupDoesNotHideViolation() {
        val unsafe = """
            1. **Features:** Home and Cart list.
            2. **Setup:** Install the free builder, don't start coding today.
            3. **Design:** Draw a rough screen sketch on paper.
        """.trimIndent()
        assertNotNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, unsafe))
        val safe = """
            1. **Features:** Home, Cart and Product list.
            2. **Warning:** Don't install a builder or start a New Project; sketch first.
            3. **Sample content:** Write down five sample item names and prices.
        """.trimIndent()
        assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, safe))
    }

    @Test fun separateDescriptiveLaterNoteIsAllowedButNotAsMainPlanningStep() {
        val valid = """
            Phone par pehle plan banao.
            1. **Features:** Minimal customer list likho.
            2. **Flow:** Rough Home/Cart screen sketch Notes par.
            3. **Content:** Five product names and prices likho.

            **Later:**
            Installing a visual builder and opening a New Project is implementation, not today's work.
        """.trimIndent()
        assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, valid))
        val invalid = """
            1. **Features:** Home and Cart list.
            2. **Flow:** Screen sketch.
            3. **Later:** Install visual builder and create a New Project.
        """.trimIndent()
        assertNotNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, invalid))
    }

    @Test fun missingMarkerCountNeverDiscadsAnswerOrBlocksActualExecutionRequest() {
        val twoClearIdeas = """
            1. **Features:** List the essentials.
            2. **Sketch:** Draw Home on paper.
        """.trimIndent()
        assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, twoClearIdeas))
        val actualBuildRequest = "Bro ab actual app implementation shuru karo, " +
            "Sketchware mein New Project banao aur screens create karo"
        assertNull(WorkspacePlanningAnswerBoundary.violation(
            actualBuildRequest,
            "Install your editor and create actual screens to build the project."
        ))
        assertNull(WorkspacePlanningAnswerBoundary.violation(
            "Hi bro kya haal hai?", "Sketchware install mat karo. 😂"
        ))
    }

    @Test fun latestVideoThreeStepHeadingsAndBoldStepFormatsAreAccepted() {
        val variants = listOf(
            """
                ## Pehle ye 3 steps
                ### Step 1: Customer features list likho
                - Home, Product, Cart.
                ### Step 2: Rough screen sketch banao Notes par
                - Bas boxes draw karo.
                ### Step 3: Sample content plan karo
                - Five product names aur prices.
            """.trimIndent(),
            """
                **Step 1: Customer flow**
                Home se cart tak paper par journey likho.
                **Step 2: Rough screen sketch**
                Notes par Home/Cart ka draft draw karo.
                **Step 3: Sample product content**
                Paanch prices likho.
            """.trimIndent(),
            """
                - **Step 1:** Features ki list
                - **Step 2:** Rough wireframe on paper
                - **Step 3:** Sample item data
            """.trimIndent()
        )
        variants.forEach { valid ->
            assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, valid))
            assertEquals(valid, WorkspacePlanningAnswerBoundary.requireAcceptable(phonePlan, valid))
        }
    }

    @Test fun noFormatOnlyRejectionWhenUsefulAdviceLacksNumericMarkdown() {
        val actualPlanning = """
            ## Features to decide
            Home, Product, Cart aur total ka short list Notes mein likho.

            ## Rough design
            Paper par screen flow draw karo.

            ## Sample content
            Five grocery item names aur prices ki list banao.
        """.trimIndent()
        assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, actualPlanning))
    }

    @Test fun evenTwoOrFourExplicitMarkersDoNotCauseFormattingRejection() {
        val two = """
            ### Step 1: List features
            ### Step 2: Sketch on paper
        """.trimIndent()
        val four = """
            1. **Features:** Write list.
            2. **Flow:** Draw journey.
            3. **Content:** Write sample prices.
            4. **Another:** Add feature ideas.
        """.trimIndent()
        assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, two))
        assertNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, four))
    }

    @Test fun setupStillFailsRegardlessOfVisualStepMarker() {
        val invalid = """
            ### Step 1: Features list
            ### Step 2: Visual builder install karo aur New Project create karo
            ### Step 3: Screen blocks connect karo
        """.trimIndent()
        assertNotNull(WorkspacePlanningAnswerBoundary.violation(phonePlan, invalid))
    }

    @Test fun comparisonIsNotMistakenForImperativeSetup() {
        val prompt = "Planning comparison do: Android Studio vs visual builder. " +
            "Just explain their requirements, do not code yet."
        val answer = """
            ## Trade-offs
            Android Studio needs desktop access. A visual builder offers another workflow.
            Both involve implementation later; no install required today.
        """.trimIndent()
        assertNull(WorkspacePlanningAnswerBoundary.violation(prompt, answer))
    }
}
