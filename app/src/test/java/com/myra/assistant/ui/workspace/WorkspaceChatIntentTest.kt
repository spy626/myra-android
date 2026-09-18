package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceChatIntentTest {
    @Test fun greetingsAndQuestionsStayChatOnly() {
        listOf("Hi", "Hello", "hello bro", "How to make a website?", "website kya hai", "Explain Android app development", "Tell me about website design").forEach {
            assertNull("Unexpected project for: $it", WorkspaceChatIntent.requestedProjectType(it))
        }
    }

    @Test fun explicitBuildRequestsAreTyped() {
        listOf("Ek website banao bro", "Make a website", "Create landing page", "वेबसाइट बनाओ").forEach {
            assertEquals(WorkspaceProjectType.WEBSITE, WorkspaceChatIntent.requestedProjectType(it))
        }
        listOf("Build an Android app", "Create a mobile app", "APK banao").forEach {
            assertEquals(WorkspaceProjectType.ANDROID_APP, WorkspaceChatIntent.requestedProjectType(it))
        }
    }
}
