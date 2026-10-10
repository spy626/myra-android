package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAppDownloadIntentTest {
    private fun msg(role: String, text: String) =
        WorkspaceConversationStore.Message(role, role, text, 1L)

    @Test fun identifiesThirdPartyAppInsteadOfGithubBuild() {
        assertEquals("Cantina", WorkspaceAppDownloadIntent.decide(
            "Cantina app ka download links do bro")?.appName)
        assertEquals("Cantina", WorkspaceAppDownloadIntent.decide(
            "Give me the Cantina app download links")?.appName)
        assertEquals("Cantina AI", WorkspaceAppDownloadIntent.decide(
            "Cantina AI App download link bhejo")?.appName)
        assertEquals("Telegram", WorkspaceAppDownloadIntent.decide(
            "Telegram app download link do")?.appName)
        assertNull(WorkspaceAppDownloadIntent.decide("GitHub pe AIRI repo ka link do"))
        assertNull(WorkspaceAppDownloadIntent.decide("LYRA app ka APK download link bhejo"))
        assertNull(WorkspaceAppDownloadIntent.decide("bro build #3374 ka APK download link bhejo"))
        assertNull(WorkspaceAppDownloadIntent.decide("APK download button fix karo"))
    }

    @Test fun shortFollowupRequiresImmediateMatchingAppRequest() {
        val preceding = listOf(
            msg("user", "Cantina app ka download links do bro"),
            msg("assistant", "Which app?")
        )
        assertEquals("Cantina", WorkspaceAppDownloadIntent.decide(
            "Cantina app ka bro", preceding)?.appName)
        assertNull(WorkspaceAppDownloadIntent.decide("Telegram app ka bro", preceding))
        assertNull(WorkspaceAppDownloadIntent.decide("Cantina app ka bro"))
        assertNull(WorkspaceAppDownloadIntent.decide("Kya haal", preceding))
    }

    @Test fun titleMatchingIsSpecificAndDoesNotUseSubstringTraps() {
        assertTrue(WorkspaceAppDownloadIntent.nameMatches("Cantina", "Cantina AI"))
        assertTrue(WorkspaceAppDownloadIntent.nameMatches("Cantina", "Cantina"))
        assertEquals(false, WorkspaceAppDownloadIntent.nameMatches(
            "Cantina", "My Cantina Wallpaper Collection"))
        assertEquals(false, WorkspaceAppDownloadIntent.nameMatches(
            "AIRI", "AirialPerspectiveEffecter"))
    }
}
