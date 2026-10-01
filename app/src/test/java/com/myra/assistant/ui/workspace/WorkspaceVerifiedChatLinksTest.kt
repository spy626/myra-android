package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceVerifiedChatLinksTest {
    @Test fun verifiedBuildLabelUsesShortNativeLinkWithoutShowingRawUrl() {
        val original = "[GitHub Build #3376](https://github.com/spy626/myra-android/actions/runs/36841743155)"
        val links = WorkspaceVerifiedChatLinks.find(original)
        assertEquals(1, links.size)
        assertEquals(original.indices, links.single().range)
        assertEquals("GitHub Build #3376", links.single().label)
        assertEquals("https://github.com/spy626/myra-android/actions/runs/36841743155", links.single().url)
    }

    @Test fun arrowLabelFromBuildReceiptIsStillOneVerifiedNativeLink() {
        val original = "[↗ Open build #3380 on GitHub]" +
            "(https://github.com/spy626/myra-android/actions/runs/36849363404)"
        val links = WorkspaceVerifiedChatLinks.find(original)
        assertEquals(1, links.size)
        assertEquals("↗ Open build #3380 on GitHub", links.single().label)
        assertEquals(
            "https://github.com/spy626/myra-android/actions/runs/36849363404",
            links.single().url,
        )
    }

    @Test fun unsafeOrUnrelatedLinksNeverGetNativeClickableSpan() {
        listOf(
            "[Open](javascript:alert(1))",
            "[Fake](https://github.com.evil.org/spy626/myra-android/actions/runs/3)",
            "[Other](http://github.com/spy626/myra-android/actions/runs/3)",
            "[Repo](https://github.com/spy626/myra-android)",
            "[Invalid](https://github.com/spy626/myra-android/actions/runs/abc)",
        ).forEach { assertTrue(it, WorkspaceVerifiedChatLinks.find(it).isEmpty()) }
    }
}
