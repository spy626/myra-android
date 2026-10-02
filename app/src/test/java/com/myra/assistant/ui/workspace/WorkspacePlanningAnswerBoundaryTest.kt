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
        assertTrue(why.orEmpty().contains("desktop"))
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

    @Test fun constraintsAreSpecificToTheCurrentTaskAndDoNotSelectAPlatform() {
        val rule = WorkspacePlanningAnswerBoundary.instructions(phonePlan)
        assertTrue(rule.contains("Exactly 3 MAIN numbered actions"))
        assertTrue(rule.contains("Phone-only access"))
        assertTrue(rule.contains("zero budget"))
        assertTrue(rule.contains("PLANNING-ONLY HARD STOP"))
        assertTrue(rule.contains("Explicit target: UNSPECIFIED"))
        assertTrue(rule.contains("Roman Hinglish"))
        assertFalse(rule.contains("SPCK"))
        assertFalse(rule.contains("grocery"))
        assertEquals("", WorkspacePlanningAnswerBoundary.instructions("hi bro"))
    }
}
