package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePracticalPlanningGuideTest {
    @Test fun generalBeginnerPlanningRequestsGetCoherentActionableGuidance() {
        val examples = listOf(
            "bro mere paas sirf Android phone hai aur mujhe free mein ek simple grocery app banana hai. Sabse pehle kya karna chahiye? 3 practical steps batao, abhi coding start mat karna 😂",
            "I have one hour a day. How should I start learning guitar? Give three doable steps.",
            "Mujhe apna portfolio start karna hai, pehle kya karu? Simple plan batao.",
            "I need a free mobile-first study workflow. Suggest a practical plan, no implementation.",
            "Our volunteer club needs an event roadmap. What should we do first?"
        )
        examples.forEach {
            val guidance = WorkspacePracticalPlanningGuide.instructions(it)
            assertTrue(it, guidance.contains("ONE coherent feasible starting route"))
            assertTrue(it, guidance.contains("exactly that many MAIN steps"))
            assertTrue(it, guidance.contains("DO and what small concrete result"))
            assertTrue(it, guidance.contains("do NOT claim to have coded"))
        }
    }

    @Test fun casualWritingOrExplicitExecutionIsNotHijackedByPlanningPrompt() {
        listOf(
            "hey bro how are you doing",
            "Just saying hi",
            "Write a funny story about a first date",
            "build a grocery app now",
            "create a website project with code and preview",
            "fix this app screenshot and edit the current file"
        ).forEach { text ->
            assertTrue(text, WorkspacePracticalPlanningGuide.instructions(text).isBlank())
        }
    }

    @Test fun constraintsStayInUserTurnAndPlanningTextGrantsNoToolPermission() {
        val guidance = WorkspacePracticalPlanningGuide.instructions(
            "I have no laptop and a zero budget. How to plan a simple website? Don't code yet."
        )
        assertTrue(guidance.contains("explicit do-not-do boundaries"))
        assertTrue(guidance.contains("separate current-turn gate"))
        assertFalse(guidance.contains("grocery"))
        assertFalse(guidance.contains("SPCK"))
        assertFalse(guidance.contains("Kodular"))
    }
}
