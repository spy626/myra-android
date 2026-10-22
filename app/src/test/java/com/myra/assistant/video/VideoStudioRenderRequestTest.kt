package com.myra.assistant.video

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStudioRenderRequestTest {
    private fun draft(
        brief: String,
        kind: VideoStudioKind = VideoStudioKind.CONSTRUCTION,
        duration: Int = 30,
    ) = VideoStudioPlanning.createDraft(
        "house_test_1", brief, kind, duration,
    )

    @Test fun approvedSchemaKeepsSixSecondRenderSeparateFromShortsTarget() {
        val request = VideoStudioRenderRequestFactory.fromDraft(
            draft("Create a two floor cream mini house with a flat roof and garden", duration = 60),
        )
        val json = JSONObject(request.toJson())
        assertEquals(1, json.getInt("request_version"))
        assertEquals("blender-mini-house-v1", json.getString("renderer"))
        assertEquals("house_test_1", json.getString("project_id"))
        assertEquals("construction", json.getString("mode"))
        assertEquals(60, json.getInt("target_duration_seconds"))
        assertEquals(6, json.getInt("preview_duration_seconds"))
        assertEquals(360, json.getInt("width"))
        assertEquals(640, json.getInt("height"))
        assertEquals(12, json.getInt("fps"))
        val spec = json.getJSONObject("spec")
        assertEquals(2, spec.getInt("floors"))
        assertEquals("cream", spec.getString("wall_color"))
        assertEquals("flat", spec.getString("roof_style"))
        assertTrue(spec.getBoolean("garden"))
        assertEquals(9, json.length())
        assertEquals(4, spec.length())
        assertFalse(request.toJson().contains("api_key"))
        assertFalse(request.toJson().contains("prompt"))
    }

    @Test fun oneFloorHinglishOrEnglishProducesDifferentRendererParameters() {
        val a = VideoStudioRenderRequestFactory.fromDraft(
            draft("Ek floor blue ghar with pitched roof without garden"),
        )
        assertEquals(VideoStudioHouseSpec(1, "blue", "gable", false), a.spec)
        val b = VideoStudioRenderRequestFactory.fromDraft(
            draft("single-floor terracotta house flat roof", duration = 60),
        )
        assertEquals(VideoStudioHouseSpec(1, "terracotta", "flat", true), b.spec)
    }

    @Test fun unsupportedModesAndConflictsAreRejected() {
        assertTrue(runCatching {
            VideoStudioRenderRequestFactory.fromDraft(
                draft("a cartoon house", VideoStudioKind.CARTOON),
            )
        }.isFailure)
        for (prompt in listOf("make a spaceship", "one floor two floor house",
                "three floor house", "house blue cream", "house flat roof and pitched roof",
                "house " + "x".repeat(500))) {
            assertTrue(prompt, runCatching {
                VideoStudioRenderRequestFactory.fromDraft(draft(prompt))
            }.isFailure)
        }
    }

    @Test fun reviewMustNotClaimExportTriggersVideoGeneration() {
        val request = VideoStudioRenderRequestFactory.fromDraft(draft("blue two floor house"))
        val review = VideoStudioRenderRequestFactory.reviewText(request)
        assertTrue(review.contains("6 seconds"))
        assertTrue(review.contains("NOT rendered yet"))
        assertTrue(review.contains("does NOT start rendering"))
        assertTrue(review.contains("Other words are ignored"))
    }

    @Test fun sameInputAlwaysCreatesIdenticalRenderSpec() {
        val input = draft("Two-storey blue ghar with gable roof and no garden")
        assertEquals(
            VideoStudioRenderRequestFactory.fromDraft(input).toJson(),
            VideoStudioRenderRequestFactory.fromDraft(input).toJson(),
        )
    }
}
