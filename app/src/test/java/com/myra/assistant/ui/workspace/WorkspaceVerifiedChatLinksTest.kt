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

    @Test fun conciseLinkWithChainIconAndExternalArrowRetainsVerifiedDestination() {
        val original = "[🔗 GitHub Build #3382 ↗]" +
            "(https://github.com/spy626/myra-android/actions/runs/36852520030)"
        val links = WorkspaceVerifiedChatLinks.find(original)
        assertEquals(1, links.size)
        assertEquals("🔗 GitHub Build #3382 ↗", links.single().label)
        assertEquals(
            "https://github.com/spy626/myra-android/actions/runs/36852520030",
            links.single().url,
        )
    }

    @Test fun verifiedDirectApkReleaseLinkIsTappable() {
        val raw = "[⬇ Download LYRA Test APK #3374]" +
            "(https://github.com/spy626/myra-android/releases/download/" +
            "airi-memory-b6bdd82a97a5/lyra-phone-test.apk)"
        val found = WorkspaceVerifiedChatLinks.find(raw)
        assertEquals(1, found.size)
        assertEquals("⬇ Download LYRA Test APK #3374", found.single().label)
        assertEquals(
            "https://github.com/spy626/myra-android/releases/download/" +
                "airi-memory-b6bdd82a97a5/lyra-phone-test.apk", found.single().url
        )
    }

    @Test fun unsafeOrUnrelatedLinksNeverGetNativeClickableSpan() {
        listOf(
            "[Open](javascript:alert(1))",
            "[Fake](https://github.com.evil.org/spy626/myra-android/actions/runs/3)",
            "[Other](http://github.com/spy626/myra-android/actions/runs/3)",
            "[Repo](https://github.com/spy626/myra-android)",
            "[Invalid](https://github.com/spy626/myra-android/actions/runs/abc)",
            "[Fake](https://github.com/spy626/myra-android/releases/download/evil-tag/lyra-phone-test.apk)",
            "[Fake](https://github.com/spy626/myra-android/releases/download/airi-memory-b6bdd82a97a5/not-apk.exe)",
        ).forEach { assertTrue(it, WorkspaceVerifiedChatLinks.find(it).isEmpty()) }
    }
}
