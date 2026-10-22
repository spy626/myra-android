package com.myra.assistant.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStudioPreviewFormatterTest {
    @Test fun thirtySecondMiniHousePreviewShowsRealLimitsAndContinuousTimeline() {
        val draft = VideoStudioPlanning.createDraft(
            "mini_house_30", "Create a modern mini house with a garden",
            VideoStudioKind.CONSTRUCTION, 30,
        )
        val text = VideoStudioPreviewFormatter.format(draft)
        assertTrue(text.contains("STORYBOARD DRAFT — NOT A VIDEO"))
        assertTrue(text.contains("Miniature construction"))
        assertTrue(text.contains("Create a modern mini house with a garden"))
        assertTrue(text.contains("0–4s"))
        assertTrue(text.contains("24–30s"))
        assertTrue(text.contains("Duration: 30 seconds"))
        assertTrue(text.contains("Format: 9:16 vertical"))
        assertTrue(text.contains("No AI scene design"))
        assertTrue(text.contains("MP4 file has been created"))
        assertFalse(text.contains("Download video"))
    }

    @Test fun sixtySecondCartoonAndExplainerAreDistinctTemplates() {
        val animal = VideoStudioPlanning.createDraft(
            "animal_60", "Original animated fish comedy", VideoStudioKind.CARTOON, 60,
        )
        val science = VideoStudioPlanning.createDraft(
            "science_60", "How volcanoes work", VideoStudioKind.EXPLAINER, 60,
        )
        val cartoon = VideoStudioPreviewFormatter.format(animal)
        val explainer = VideoStudioPreviewFormatter.format(science)
        assertTrue(cartoon.contains("Original cartoon"))
        assertTrue(cartoon.contains("visual punchline"))
        assertTrue(explainer.contains("3D explainer"))
        assertTrue(explainer.contains("closing reveal"))
        assertTrue(cartoon.contains("47–60s"))
        assertTrue(explainer.contains("49–60s"))
        assertTrue(explainer.contains("Duration: 60 seconds"))
    }

    @Test fun previewIsDeterministicAndDoesNotClaimOutputExists() {
        val draft = VideoStudioPlanning.createDraft(
            "same_project", "House walls, windows and roof", VideoStudioKind.CONSTRUCTION, 60,
        )
        val first = VideoStudioPreviewFormatter.format(draft)
        assertTrue(first == VideoStudioPreviewFormatter.format(draft))
        assertTrue(first.contains("Template timing only"))
        assertFalse(first.contains("Render complete"))
        assertFalse(first.contains("Video saved"))
    }
}
