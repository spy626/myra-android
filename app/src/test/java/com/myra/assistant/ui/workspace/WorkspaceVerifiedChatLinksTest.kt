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

    @Test fun arbitrarySafePublicHttpsMarkdownLinksAreTappable() {
        val cases = listOf(
            "[OpenAI](https://openai.com/)" to "https://openai.com/",
            "[YouTube](https://www.youtube.com/)" to "https://www.youtube.com/",
            "[Android docs](https://developer.android.com/guide)" to
                "https://developer.android.com/guide",
            "[Example article](https://example.com/news?id=7#details)" to
                "https://example.com/news?id=7#details",
        )
        cases.forEach { (raw, expected) ->
            val found = WorkspaceVerifiedChatLinks.find(raw)
            assertEquals(raw, 1, found.size)
            assertEquals(raw, expected, found.single().url)
        }
    }

    @Test fun bareSafeHttpsUrlIsAlsoTappable() {
        val raw = "Official site: https://openai.com/."
        val found = WorkspaceVerifiedChatLinks.find(raw)
        assertEquals(1, found.size)
        assertEquals("https://openai.com/", found.single().label)
        assertEquals("https://openai.com/", found.single().url)
    }

    @Test fun unsafeUrlsNeverGetNativeClickableSpan() {
        listOf(
            "[Open](javascript:alert(1))",
            "[HTTP](http://example.com/)",
            "[Local](https://localhost/admin)",
            "[Private](https://127.0.0.1/admin)",
            "[Metadata](https://169.254.169.254/latest)",
            "[Credential](https://example.com/?token=secret)",
            "http://example.com/",
        ).forEach { assertTrue(it, WorkspaceVerifiedChatLinks.find(it).isEmpty()) }
    }

    @Test fun markdownDestinationIsNotDuplicatedAsBareLink() {
        val raw = "[OpenAI](https://openai.com/) and https://example.com/docs"
        val found = WorkspaceVerifiedChatLinks.find(raw)
        assertEquals(2, found.size)
        assertEquals("OpenAI", found[0].label)
        assertEquals("https://example.com/docs", found[1].label)
    }
}
