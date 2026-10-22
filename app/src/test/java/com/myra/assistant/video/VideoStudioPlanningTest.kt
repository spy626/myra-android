package com.myra.assistant.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStudioPlanningTest {
    @Test fun miniatureHouseThirtySecondDraftHasContiguousBeats() {
        val draft = VideoStudioPlanning.createDraft(
            projectId = "mini_house_001",
            rawPrompt = "  Build a modern two-floor mini house  ",
            kind = VideoStudioKind.CONSTRUCTION,
            durationSeconds = 30,
        )

        assertEquals("Build a modern two-floor mini house", draft.prompt)
        assertEquals("9:16", draft.aspectRatio)
        assertEquals(VideoStudioCharacterPolicy.NEW_FOR_EACH_PROJECT, draft.characterPolicy)
        val beats = VideoStudioPlanning.draftBeats(draft)
        assertEquals(0, beats.first().startSecond)
        assertEquals(30, beats.last().endSecond)
        assertEquals(5, beats.size)
        assertTrue(beats.first().purpose.contains("finished build"))
        assertTrue(beats.last().purpose.contains("Reveal"))
        assertEquals(30, beats.sumOf { it.endSecond - it.startSecond })
        assertTrue(beats.zipWithNext().all { (first, second) ->
            first.endSecond == second.startSecond
        })
    }

    @Test fun sixtySecondConstructionAddsDetailsWithoutChangingProjectIdentity() {
        val draft = VideoStudioPlanning.createDraft(
            "build_002", "Mini house with a garden", VideoStudioKind.CONSTRUCTION, 60,
        )
        val beats = VideoStudioPlanning.draftBeats(draft)
        assertEquals("build_002", draft.projectId)
        assertEquals(6, beats.size)
        assertEquals(60, beats.last().endSecond)
        assertTrue(beats.any { it.purpose.contains("lighting") })
    }

    @Test fun differentProjectsDoNotRequirePermanentCharacters() {
        val animal = VideoStudioPlanning.createDraft(
            "cartoon_animal", "Silly sea animal chase", VideoStudioKind.CARTOON, 30,
        )
        val robot = VideoStudioPlanning.createDraft(
            "cartoon_robot", "A playful robot prank", VideoStudioKind.CARTOON, 60,
        )
        assertFalse(animal.projectId == robot.projectId)
        assertFalse(animal.prompt == robot.prompt)
        assertEquals(VideoStudioCharacterPolicy.NEW_FOR_EACH_PROJECT, animal.characterPolicy)
        assertEquals(VideoStudioCharacterPolicy.NEW_FOR_EACH_PROJECT, robot.characterPolicy)
        assertEquals(30, VideoStudioPlanning.draftBeats(animal).last().endSecond)
        assertEquals(60, VideoStudioPlanning.draftBeats(robot).last().endSecond)
    }

    @Test fun educationalShortProducesOnlyDraftBeats() {
        val draft = VideoStudioPlanning.createDraft(
            "explainer_1", "How does a volcano erupt?", VideoStudioKind.EXPLAINER, 30,
        )
        val beats = VideoStudioPlanning.draftBeats(draft)
        assertTrue(beats.first().purpose.contains("question"))
        assertTrue(beats.last().purpose.contains("closing reveal"))
    }

    @Test fun unsupportedDurationsAndInvalidPromptsFailClosed() {
        assertTrue(runCatching {
            VideoStudioPlanning.createDraft("test", "House", VideoStudioKind.CONSTRUCTION, 120)
        }.isFailure)
        assertTrue(runCatching {
            VideoStudioPlanning.createDraft("test", "   ", VideoStudioKind.CARTOON, 30)
        }.isFailure)
        assertTrue(runCatching {
            VideoStudioPlanning.createDraft("../other", "House", VideoStudioKind.CONSTRUCTION, 30)
        }.isFailure)
        assertTrue(runCatching {
            VideoStudioPlanning.createDraft("test", "x".repeat(1501),
                VideoStudioKind.CONSTRUCTION, 30)
        }.isFailure)
        assertTrue(runCatching {
            VideoStudioPlanning.createDraft("test", "house\u0000", VideoStudioKind.CONSTRUCTION, 30)
        }.isFailure)
    }
}
