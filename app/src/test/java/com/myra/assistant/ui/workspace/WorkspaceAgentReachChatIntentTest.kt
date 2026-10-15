package com.myra.assistant.ui.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentReachChatIntentTest {
    @Test fun bareSupportedGithubLinkIsAReadRequest() {
        val d = WorkspaceAgentReachChatIntent.decide(
            "https://github.com/browser-use/jev-ultrafast")
        assertNotNull(d?.target)
        assertNull(d?.localError)
    }

    @Test fun explicitHinglishAndEnglishReadRequestsAreAccepted() {
        listOf(
            "https://github.com/a/b isko check kro bro",
            "Please inspect https://github.com/a/b/blob/main/README.md",
            "ye repo dekho https://github.com/a/b",
            "read https://raw.githubusercontent.com/a/b/main/README.md",
            "Research https://example.com/guide security updates",
            "Explore https://example.com/guide for installation",
        ).forEach { text ->
            assertNotNull("Read intent missed: $text",
                WorkspaceAgentReachChatIntent.decide(text)?.target)
        }
    }

    @Test fun casuallyMentionedLinkDoesNotSilentlyOpen() {
        assertNull(WorkspaceAgentReachChatIntent.decide(
            "My friend sent https://github.com/a/b yesterday"))
        assertNull(WorkspaceAgentReachChatIntent.decide(
            "Don't open https://github.com/a/b"))
        assertNull(WorkspaceAgentReachChatIntent.decide(
            "https://github.com/a/b check mat karna"))
    }

    @Test fun unsupportedOrMultipleGithubReadsFailLocally() {
        val tree = WorkspaceAgentReachChatIntent.decide(
            "check https://github.com/a/b/tree/main/src")
        assertNull(tree?.target)
        assertNotNull(tree?.localError)

        val multiple = WorkspaceAgentReachChatIntent.decide(
            "compare and check https://github.com/a/b and https://github.com/c/d")
        assertNull(multiple?.target)
        assertTrue(multiple?.localError.orEmpty().contains("one public HTTPS link"))
    }

    @Test fun normalPublicWebpageGetsItsOwnReadOnlyRoute() {
        val web = WorkspaceAgentReachChatIntent.decide("check https://example.com/docs")
        assertEquals(WorkspaceAgentReachPolicy.Platform.WEB, web?.target?.platform)
        assertNull(web?.localError)
        assertNull(WorkspaceAgentReachChatIntent.decide(
            "My friend sent https://example.com/docs yesterday"))
        assertNull(WorkspaceAgentReachChatIntent.decide(
            "Don't open https://example.com/docs"))
        assertNull(WorkspaceAgentReachChatIntent.decide(
            "Don't research https://example.com/docs"))
        assertNull(WorkspaceAgentReachChatIntent.decide(
            "research https://example.com/docs mat"))
        val two = WorkspaceAgentReachChatIntent.decide(
            "read https://github.com/a/b and https://example.com/docs")
        assertNull(two?.target)
        assertTrue(two?.localError.orEmpty().contains("one public HTTPS link"))
    }
}
