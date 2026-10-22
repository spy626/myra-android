package com.myra.assistant.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStudioJobLinksTest {
    @Test fun numericRunIdBuildsReadOnlyOwnedRepositoryUrl() {
        assertEquals(
            "https://github.com/spy626/myra-android/actions/runs/39849196163",
            VideoStudioJobLinks.statusPage(" 39849196163 "),
        )
        assertEquals(
            "https://github.com/spy626/myra-android/actions/workflows/video-studio-slice-6.yml",
            VideoStudioJobLinks.WORKFLOW_PAGE,
        )
    }

    @Test fun neverAllowArbitraryBrowserLocations() {
        for (bad in listOf(
                "", "0", "-1", "12/../../settings", "https://evil.invalid",
                "https://github.com/other/repository/actions/runs/1",
                "123a", "1\n2", "999999999999999999999",
        )) {
            assertTrue(bad, runCatching { VideoStudioJobLinks.statusPage(bad) }.isFailure)
        }
    }

    @Test fun statusNoticeDoesNotClaimAutomaticSubmissionOrSuccess() {
        val notice = VideoStudioJobLinks.BOUNDARY_NOTICE
        assertTrue(notice.contains("Manual GitHub owner approval"))
        assertTrue(notice.contains("never submits jobs"))
        assertTrue(notice.contains("default branch"))
        assertFalse(notice.contains("Completed successfully"))
    }
}
