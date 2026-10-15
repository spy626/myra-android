package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceStablePlatformLinkTest {
    @Test fun barePlatformLinkRequestsUseStableOfficialHomepages() {
        assertEquals("https://www.youtube.com/",
            WorkspaceStablePlatformLink.decide("YouTube ka link bhejo")?.url)
        assertEquals("https://github.com/",
            WorkspaceStablePlatformLink.decide("GitHub ka URL do")?.url)
        assertEquals("https://openai.com/",
            WorkspaceStablePlatformLink.decide("OpenAI website ka link do")?.url)
    }

    @Test fun namedOrDeepPageRequestsDoNotCollapseToHomepage() {
        assertNull(WorkspaceStablePlatformLink.decide("CarryMinati ka YouTube link bhejo"))
        assertNull(WorkspaceStablePlatformLink.decide("OpenAI API docs ka link do"))
        assertNull(WorkspaceStablePlatformLink.decide("GitHub pe AIRI repo ka link do"))
        assertNull(WorkspaceStablePlatformLink.decide("GitHub link feature add karo"))
    }
}
