package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model plans intent; only existing authoritative read-only pipeline executes research. */
class WorkspaceIntelligentUnderstandingTest {
    @Test fun understandsResearchGoalAcrossDifferentNaturalWordings() {
        val choices = listOf(
            """{"route":"PUBLIC_RESEARCH","searchQuery":"Android iOS battery optimization"}""",
            """{"route":"PUBLIC_RESEARCH","searchQuery":"iPhone Android power management and battery life"}""",
            """{"route":"PUBLIC_RESEARCH","searchQuery":"Kotlin versus Java Android development differences"}""",
            """{"route":"PUBLIC_RESEARCH","searchQuery":"current monsoon rainfall projections India"}""",
        )
        choices.forEach { raw ->
            val plan = requireNotNull(WorkspaceIntelligentUnderstanding.parse(raw))
            assertEquals(WorkspaceIntelligentUnderstanding.Route.PUBLIC_RESEARCH, plan.route)
            assertTrue(plan.query.length >= 5)
        }
    }

    @Test fun normalChatAndPersonalFollowUpsNeverBecomePublicWebReads() {
        val simple = requireNotNull(WorkspaceIntelligentUnderstanding.parse(
            """{"route":"CHAT","searchQuery":"private history"}"""))
        assertEquals(WorkspaceIntelligentUnderstanding.Route.CHAT, simple.route)
        assertEquals("", simple.query)
        assertEquals(WorkspaceIntelligentUnderstanding.Route.CHAT,
            WorkspaceIntelligentUnderstanding.parse(
                """{"route":"CHAT","searchQuery":""}""")?.route)
    }

    @Test fun modelCannotGrantToolWritesOrArbitraryUrlReads() {
        assertNull(WorkspaceIntelligentUnderstanding.parse(
            """{"route":"WRITE_GITHUB","searchQuery":"commit to main"}"""))
        assertNull(WorkspaceIntelligentUnderstanding.parse(
            """{"route":"DOWNLOAD_APK","searchQuery":"app install"}"""))
        assertNull(WorkspaceIntelligentUnderstanding.parse(
            """{"route":"PUBLIC_RESEARCH","searchQuery":"https://private.example/account"}"""))
        assertNull(WorkspaceIntelligentUnderstanding.parse(
            """{"route":"PUBLIC_RESEARCH","searchQuery":""}"""))
    }

    @Test fun invalidPlannerOutputFallsBackInsteadOfGuessingFromWords() {
        assertNull(WorkspaceIntelligentUnderstanding.parse(
            "I think the user is asking about Android"))
        assertNull(WorkspaceIntelligentUnderstanding.parse("not-json"))
        assertEquals(WorkspaceIntelligentUnderstanding.Route.PUBLIC_RESEARCH,
            WorkspaceIntelligentUnderstanding.parse(
                "\u0060\u0060\u0060json\n" +
                    """{"route":"PUBLIC_RESEARCH","searchQuery":"Android iOS battery optimization"}""" +
                    "\n\u0060\u0060\u0060")?.route)
    }

    @Test fun plannerPromptRequestsUnderstandingNotKeywordOrExecutionRules() {
        val instructions = WorkspaceIntelligentUnderstanding.SYSTEM_INSTRUCTIONS
        assertTrue(instructions.contains("ACTUAL intended outcome"))
        assertTrue(instructions.contains("NOT matching example words"))
        assertTrue(instructions.contains("never route", ignoreCase = true))
        assertTrue(instructions.contains("No explanation or Markdown"))
    }

    @Test fun trivialChatUsesExistingFastModelPathWithoutExtraPlanning() {
        assertFalse(WorkspaceIntelligentUnderstanding.shouldPlan("Haan bro!"))
        assertFalse(WorkspaceIntelligentUnderstanding.shouldPlan(""))
        assertTrue(WorkspaceIntelligentUnderstanding.shouldPlan(
            "LYRA, Android aur iOS ki battery optimization compare karo."))
        assertTrue(WorkspaceIntelligentUnderstanding.shouldPlan(
            "Mera phone discharge kyu ho raha hai?"))
    }
}
