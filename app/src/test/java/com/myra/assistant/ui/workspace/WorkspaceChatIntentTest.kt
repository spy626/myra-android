package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChatIntentTest {
    @Test fun greetingsAndQuestionsStayChatOnly() {
        listOf(
            "Hi",
            "Hello",
            "hello bro",
            "How to make a website?",
            "website kya hai",
            "Explain Android app development",
            "Tell me about website design",
            "What would you change in this file?",
            "Should I build a website?"
        ).forEach {
            assertNull("Unexpected project for: $it", WorkspaceChatIntent.requestedProjectType(it))
        }
    }

    @Test fun writingOrPlanningDevelopmentDoesNotExecute() {
        listOf(
            "Build Android app ka prompt do",
            "Make a website plan for me",
            "Create a roadmap for an Android app",
            "Generate ideas for a landing page"
        ).forEach {
            assertNull("Planning text executed: $it", WorkspaceChatIntent.requestedProjectType(it))
        }
        assertFalse(WorkspaceChatIntent.isCodingFollowUp("Add hands-free to my Android app prompt"))
        assertFalse(WorkspaceChatIntent.isCodingFollowUp("Fix my AI companion development prompt"))
        assertFalse(WorkspaceChatIntent.isCodingFollowUp("Give me a plan to redesign it"))
    }

    @Test fun negatedOrDeferredExecutionStaysInChat() {
        listOf(
            "Mujhe kal ek chhoti website banani hai. Abhi code mat likhna, bas mujhse ek useful question pucho.",
            "Build a website, but don't code it yet. Ask me what style I want first.",
            "Website abhi mat banao; pehle options batao.",
            "Create an Android app later; for now explain the architecture.",
            "Make a website, but for now just ask me one question."
        ).forEach {
            assertNull("Blocked request executed: $it", WorkspaceChatIntent.requestedProjectType(it))
        }

        listOf(
            "Don't change anything yet, just tell me what you would fix.",
            "Explain how to fix this code.",
            "Review this file only; no changes.",
            "Update it later, for now just explain the issue."
        ).forEach {
            assertFalse("Blocked edit executed: $it", WorkspaceChatIntent.isCodingFollowUp(it))
        }
    }

    @Test fun laterAffirmativeInstructionCanOverrideEarlierConstraint() {
        assertEquals(
            WorkspaceProjectType.WEBSITE,
            WorkspaceChatIntent.requestedProjectType(
                "Don't use React, build a website in plain HTML and CSS."
            )
        )
        assertEquals(
            WorkspaceProjectType.ANDROID_APP,
            WorkspaceChatIntent.requestedProjectType(
                "Don't build a website; instead create an Android app."
            )
        )
        assertTrue(WorkspaceChatIntent.isCodingFollowUp(
            "Do not remove login; change the button color to red."
        ))
        assertTrue(WorkspaceChatIntent.isCodingFollowUp(
            "Review the issue and then fix the button alignment."
        ))
    }

    @Test fun explicitBuildRequestsAreTyped() {
        listOf(
            "Ek website banao bro",
            "Make a website",
            "Create landing page",
            "वेबसाइट बनाओ",
            "Can you build me a website?"
        ).forEach {
            assertEquals(WorkspaceProjectType.WEBSITE, WorkspaceChatIntent.requestedProjectType(it))
        }
        listOf(
            "Build an Android app",
            "Create a mobile app",
            "APK banao"
        ).forEach {
            assertEquals(WorkspaceProjectType.ANDROID_APP, WorkspaceChatIntent.requestedProjectType(it))
        }
    }

    @Test fun ambiguousTwoProjectRequestDoesNotGuessExecutionTarget() {
        assertNull(WorkspaceChatIntent.requestedProjectType("Build a website or Android app"))
    }
}
