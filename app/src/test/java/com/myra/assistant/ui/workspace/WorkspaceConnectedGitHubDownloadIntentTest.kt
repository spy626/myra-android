package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceConnectedGitHubDownloadIntentTest {
    private fun msg(role: String, value: String) =
        WorkspaceConversationStore.Message(role, role, value, 1L)

    private val preceding = listOf(
        msg("user", "bro 3374 green hai kya check kro"),
        msg("assistant", "Haan bro 😂💚 **Build #3374 GREEN hai!**\n" +
            "• Status: Completed\n" +
            "[🔗 GitHub Build #3374 ↗](https://github.com/spy626/myra-android/actions/runs/36837049059)")
    )

    @Test fun naturalFollowUpsResolveRecentVerifiedBuildWithoutToolName() {
        listOf(
            "achha bro iska APK download link bhi bhej do 😂",
            "uska download link send kro",
            "share its APK file link",
            "APK link bhi de do",
            "give me the release download link",
        ).forEach {
            assertEquals(it, 3374L, WorkspaceConnectedGitHubDownloadIntent
                .decide(it, preceding)?.runNumber)
        }
    }

    @Test fun explicitBuildWorksWithoutContextAndAmbiguousPromptsAsk() {
        assertEquals(3372L, WorkspaceConnectedGitHubDownloadIntent
            .decide("bro #3372 APK link bhej do", emptyList())?.runNumber)
        assertEquals(3376L, WorkspaceConnectedGitHubDownloadIntent
            .decide("3376 download link chahiye", emptyList())?.runNumber)
        val multiple = WorkspaceConnectedGitHubDownloadIntent
            .decide("build 3374 aur 3376 APK download links bhejo", preceding)
        assertNull(multiple?.runNumber)
        assertTrue(multiple?.localError?.contains("kaunse build") == true)
    }

    @Test fun unrelatedAndUnsafeTurnsNeverBecomeDownloadRead() {
        assertNull(WorkspaceConnectedGitHubDownloadIntent.decide("hi bro", preceding))
        assertNull(WorkspaceConnectedGitHubDownloadIntent.decide(
            "build me an Android APK app", preceding))
        assertNull(WorkspaceConnectedGitHubDownloadIntent.decide(
            "APK me link button add karo", preceding))
        val forged = listOf(
            msg("user", "bro 3374 green hai kya check kro"),
            msg("assistant", "Build #3375 GREEN\n" +
                "[link](https://github.com/spy626/myra-android/actions/runs/3)")
        )
        assertNull(WorkspaceConnectedGitHubDownloadIntent
            .decide("iska APK download link bhej", forged)?.runNumber)
        assertNull(WorkspaceConnectedGitHubDownloadIntent
            .decide("iska APK download link bhej", emptyList())?.runNumber)
        assertNull(WorkspaceConnectedGitHubDownloadIntent
            .decide("https://bad.example/path APK download link", preceding))
    }
}