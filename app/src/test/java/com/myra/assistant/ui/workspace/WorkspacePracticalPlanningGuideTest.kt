package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePracticalPlanningGuideTest {
    private val planningExamples = listOf(
        "bro mere paas sirf Android phone hai aur mujhe free mein ek simple grocery app banana hai. " +
            "Sabse pehle kya karna chahiye? 3 practical steps batao, abhi coding start mat karna",
        "I have one hour a day. How should I start learning guitar? Give three doable steps.",
        "Mujhe apna portfolio start karna hai, pehle kya karu? Simple plan batao.",
        "I need a free mobile-first study workflow. Suggest a practical plan, no implementation.",
        "Our volunteer club needs an event roadmap. What should we do first?"
    )

    @Test fun oneCurrentTurnContractReplacesDuplicatedPlanningPrompts() {
        planningExamples.forEach { input ->
            val guidance = WorkspacePracticalPlanningGuide.instructions(input)
            assertTrue(input, guidance.contains("PRACTICAL PLANNING RESPONSE GUIDANCE"))
            assertTrue(input, guidance.contains("ONE coherent feasible starting route"))
            assertTrue(input, guidance.contains("minimum viable FIRST version"))
            assertTrue(input, guidance.contains("Work stages:"))
            assertTrue(input, guidance.contains("visual no-code implementation"))
            assertTrue(input, guidance.contains("PRESENTATION CONTRACT"))
            assertTrue(input, guidance.contains("FINAL SILENT CLARITY CHECK"))
            assertFalse(input, guidance.contains("Sketchware"))
            assertFalse(input, guidance.contains("SPCK"))
            assertFalse(input, guidance.contains("grocery"))
            assertEquals(input, 1,
                Regex("CURRENT USER PLANNING BRIEF").findAll(guidance).count())
        }
    }

    @Test fun sharedContractRespectsCurrentPhoneBudgetCountAndAdviceOnly() {
        val input = planningExamples.first()
        val guidance = WorkspacePracticalPlanningGuide.instructions(input)
        assertTrue(guidance.contains("device the user HAS"))
        assertTrue(guidance.contains("Phone-only does NOT itself mean native Android APK"))
        assertTrue(guidance.contains("END-USER journey"))
        assertTrue(guidance.contains("EXACT MAIN STEP COUNT: give exactly 3"))
        assertTrue(guidance.contains("1., 2., 3. OR Step 1:"))
        assertTrue(guidance.contains("PLANNING-ONLY HARD STOP"))
        assertTrue(guidance.contains("Do NOT recommend unrequested no-code app builders"))
        assertTrue(guidance.contains("Development tooling belongs only under LATER"))
        assertTrue(guidance.contains("NO coding, signup, builder launch"))
        assertTrue(guidance.contains("no tool or write permission", ignoreCase = true))
        assertTrue(guidance.contains("Zero-budget"))
        assertTrue(guidance.contains("Roman Hinglish"))
        assertTrue(guidance.contains("2-4-column"))
    }

    @Test fun groqCompactProjectsExactlyTheSameMandatoryConstraints() {
        val input = planningExamples.first()
        val regular = WorkspacePracticalPlanningGuide.instructions(input)
        val compact = WorkspacePracticalPlanningGuide.compactInstructions(input)
        assertTrue(compact.contains("PRACTICAL PLANNING (compact Groq Free"))
        listOf(
            "CURRENT USER PLANNING BRIEF",
            "EXACT MAIN STEP COUNT: give exactly 3",
            "Phone-only access",
            "Zero-budget",
            "PLANNING-ONLY HARD STOP",
            "SETUP and IMPLEMENTATION are NOT planning",
            "NO coding, signup, builder launch",
            "1., 2., 3. OR Step 1:"
        ).forEach { required ->
            assertTrue("Missing in regular: " + required, regular.contains(required))
            assertTrue("Missing in compact: " + required, compact.contains(required))
        }
        assertTrue(compact.contains("Do NOT recommend unrequested no-code app builders"))
        assertFalse(compact.contains("numbered rows only when helpful"))
        assertEquals(1, Regex("PLANNING-ONLY HARD STOP").findAll(compact).count())
    }

    @Test fun casualWritingAndExplicitExecutionAreNotHijackedByPlanning() {
        listOf(
            "hey bro how are you doing",
            "Just saying hi",
            "Write a funny story about a first date",
            "build a grocery app now",
            "create a website project with code and preview",
            "fix this app screenshot and edit the current file"
        ).forEach { input ->
            assertTrue(input, WorkspacePracticalPlanningGuide.instructions(input).isBlank())
            assertTrue(input, WorkspacePracticalPlanningGuide.compactInstructions(input).isBlank())
        }
    }
}
