package com.myra.assistant.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStudioSubmissionPackageTest {
    private fun request(
        id: String = "mini_house_demo",
        floors: Int = 1,
        color: String = "blue",
        roof: String = "gable",
        garden: Boolean = false,
        target: Int = 30,
    ) = VideoStudioRenderRequest(
        projectId = id,
        targetSeconds = target,
        spec = VideoStudioHouseSpec(floors, color, roof, garden),
    )

    @Test fun preparedPayloadContainsOnlySixApprovedGithubWorkflowFields() {
        val pkg = VideoStudioSubmissionFactory.prepare(request())
        assertEquals(
            listOf("project_id", "floors", "color", "roof", "garden", "target"),
            pkg.inputs().keys.toList(),
        )
        assertEquals(
            listOf("mini_house_demo", "1", "blue", "gable", "no", "30"),
            pkg.inputs().values.toList(),
        )
        assertEquals("7a15c74a1779d3aa9ac0ccb6519d8d605a079840f5d25d3b86e0520924c6782e",
            pkg.fingerprint())
        assertEquals(64, pkg.fingerprint().length)
        assertTrue(pkg.fingerprint().all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(pkg.copyableInputs().contains("Bearer"))
        assertFalse(pkg.copyableInputs().contains("password"))
        assertFalse(pkg.copyableInputs().contains("api_key"))
        assertFalse(pkg.copyableInputs().contains("user_prompt"))
    }

    @Test fun validatesInputsAtTheFinalApprovalBoundaryEvenIfConstructedManually() {
        val invalid = listOf(
            request(id = "../main"),
            request(id = "x".repeat(81)),
            request(floors = 3),
            request(color = "purple"),
            request(roof = "spherical"),
            request(target = 6),
            request(target = 120),
        )
        invalid.forEachIndexed { i, item ->
            assertTrue("case #$i", runCatching {
                VideoStudioSubmissionFactory.prepare(item)
            }.isFailure)
        }
    }

    @Test fun approvalFingerprintBindsAllSupportedInputs() {
        val a = VideoStudioSubmissionFactory.prepare(request())
        val b = VideoStudioSubmissionFactory.prepare(request())
        assertEquals(a.fingerprint(), b.fingerprint())
        val alternatives = listOf(
            request(id = "other_project"),
            request(floors = 2),
            request(color = "cream"),
            request(roof = "flat"),
            request(garden = true),
            request(target = 60),
        )
        for (other in alternatives) {
            assertNotEquals(a.fingerprint(),
                VideoStudioSubmissionFactory.prepare(other).fingerprint())
        }
    }

    @Test fun reviewIsTransparentAboutManualAuthAndSixSecondRender() {
        val review = VideoStudioSubmissionFactory.prepare(request()).humanReview()
        assertTrue(review.contains("SIX seconds"))
        assertTrue(review.contains("does NOT submit"))
        assertTrue(review.contains("not a security signature"))
        assertTrue(review.contains("Only a GitHub-authorized owner"))
        assertTrue(review.contains("separately approved"))
        assertFalse(review.contains("Video saved"))
        assertFalse(review.contains("Submitting now"))
    }

    @Test fun userPromptNeverAppearsInManualDispatchOptions() {
        val draft = VideoStudioPlanning.createDraft(
            "house_one", "Build a blue single-floor house with a gable roof and no garden" +
                " with my favorite unrelated personal story",
            VideoStudioKind.CONSTRUCTION, 60,
        )
        val pkg = VideoStudioSubmissionFactory.prepare(
            VideoStudioRenderRequestFactory.fromDraft(draft),
        )
        assertEquals("blue", pkg.inputs()["color"])
        assertEquals("1", pkg.inputs()["floors"])
        assertFalse(pkg.humanReview().contains("unrelated personal story"))
        assertFalse(pkg.copyableInputs().contains("unrelated personal story"))
    }
}
