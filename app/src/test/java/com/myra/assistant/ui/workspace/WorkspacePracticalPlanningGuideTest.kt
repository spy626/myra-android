package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePracticalPlanningGuideTest {
    private val planningExamples = listOf(
        "bro mere paas sirf Android phone hai aur mujhe free mein ek simple grocery app banana hai. " +
            "Sabse pehle kya karna chahiye? 3 practical steps batao, abhi coding start mat karna 😂",
        "I have one hour a day. How should I start learning guitar? Give three doable steps.",
        "Mujhe apna portfolio start karna hai, pehle kya karu? Simple plan batao.",
        "I need a free mobile-first study workflow. Suggest a practical plan, no implementation.",
        "Our volunteer club needs an event roadmap. What should we do first?"
    )

    @Test fun taskRelevantGuideKeepsStageAndUserConstraintsWithoutCannedBuilderNames() {
        planningExamples.forEach { input ->
            val guidance = WorkspacePracticalPlanningGuide.instructions(input)
            assertTrue(input, guidance.contains("PRACTICAL PLANNING RESPONSE GUIDANCE"))
            assertTrue(input, guidance.contains("ONE coherent feasible starting route"))
            assertTrue(input, guidance.contains("minimum viable FIRST version"))
            assertTrue(input, guidance.contains("Work stages:"))
            assertTrue(input, guidance.contains("SETUP"))
            assertTrue(input, guidance.contains("connecting visual blocks"))
            assertTrue(input, guidance.contains("PRESENTATION CONTRACT"))
            assertTrue(input, guidance.contains("FINAL SILENT CLARITY CHECK"))
            assertTrue(input, guidance.contains("do NOT claim to have coded"))
            assertFalse(input, guidance.contains("Sketchware"))
            assertFalse(input, guidance.contains("SPCK"))
            assertFalse(input, guidance.contains("grocery"))
        }
    }

    @Test fun deviceIsSeparatedFromTargetAndAdviceOnlyExcludesNoCodeImplementation() {
        val guidance = WorkspacePracticalPlanningGuide.instructions(
            "I only have an Android phone and no money. What are 3 first steps for a " +
                "simple booking app? Don't code yet."
        )
        assertTrue(guidance.contains("device the user HAS"))
        assertTrue(guidance.contains("Phone-only does NOT itself mean native Android APK"))
        assertTrue(guidance.contains("END-USER journey"))
        assertTrue(guidance.contains("New Project"))
        assertTrue(guidance.contains("visual no-code implementation"))
        assertTrue(guidance.contains("rough screen sketches"))
        assertTrue(guidance.contains("separate current-turn gate"))
        assertTrue(guidance.contains("Roman Hinglish"))
        assertTrue(guidance.contains("2-4-column"))
    }

    @Test fun compactGroqKeepsPlanningHardStopAndCountWithoutExtraCalls() {
        val input = planningExamples.first()
        val compact = WorkspacePracticalPlanningGuide.compactInstructions(input)
        assertTrue(compact.contains("PRACTICAL PLANNING (compact Groq Free"))
        assertTrue(compact.contains("Requested MAIN steps: 3"))
        assertTrue(compact.contains("planning-before-code: true"))
        assertTrue(compact.contains("NO coding, signup, builder launch"))
        assertTrue(compact.contains("Phone-only + advice-only"))
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
        }
    }
}
