package com.myra.assistant.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTargetResolverTest {
    private val width = 1_000
    private val height = 2_000

    private fun candidate(
        id: Int,
        label: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        role: String = "video"
    ) = ScreenTargetCandidate(id, label, role, left, top, right, bottom)

    @Test fun centerUsesBothAxesAndSelectsRequestedElementType() {
        val candidates = listOf(
            candidate(1, "Left video", 20, 700, 320, 1_100),
            candidate(2, "Center video", 350, 750, 650, 1_150),
            candidate(3, "Settings", 450, 900, 550, 1_020, role = "button")
        )

        val result = ScreenTargetResolver.resolve(candidates, "video", "center", null, width, height)
            as ScreenTargetResolution.Selected
        assertEquals(2, result.candidate.id)
    }

    @Test fun leftAndRightSelectCandidatesByHorizontalBounds() {
        val candidates = listOf(
            candidate(1, "First video", 20, 500, 320, 900),
            candidate(2, "Second video", 680, 500, 980, 900)
        )
        val left = ScreenTargetResolver.resolve(candidates, "video", "left", null, width, height)
            as ScreenTargetResolution.Selected
        val right = ScreenTargetResolver.resolve(candidates, "video", "right", null, width, height)
            as ScreenTargetResolution.Selected
        assertEquals(1, left.candidate.id)
        assertEquals(2, right.candidate.id)
    }

    @Test fun exactAndPartialTitlesResolveVisibleVideo() {
        val candidates = listOf(
            candidate(1, "Cooking dinner quickly", 10, 200, 990, 500),
            candidate(2, "How to Build AI Agents with Gemini", 10, 600, 990, 900)
        )
        val exact = ScreenTargetResolver.resolve(
            candidates, "How to Build AI Agents with Gemini", null, null, width, height
        ) as ScreenTargetResolution.Selected
        val partial = ScreenTargetResolver.resolve(
            candidates, "video about building AI agents", null, null, width, height
        ) as ScreenTargetResolution.Selected
        assertEquals(2, exact.candidate.id)
        assertEquals(2, partial.candidate.id)
    }

    @Test fun similarlyRankedTitlesAskForClarification() {
        val candidates = listOf(
            candidate(1, "Build AI Agents part one", 10, 200, 990, 500),
            candidate(2, "Build AI Agents part two", 10, 600, 990, 900)
        )
        assertTrue(
            ScreenTargetResolver.resolve(candidates, "AI agents video", null, null, width, height) is
                ScreenTargetResolution.Ambiguous
        )
    }

    @Test fun ordinalIsOneBasedAndNeverFallsBackToRandomCandidate() {
        val candidates = listOf(
            candidate(1, "First", 10, 200, 990, 500),
            candidate(2, "Second", 10, 600, 990, 900)
        )
        val second = ScreenTargetResolver.resolve(candidates, "video", null, 2, width, height)
            as ScreenTargetResolution.Selected
        assertEquals(2, second.candidate.id)
        assertTrue(ScreenTargetResolver.resolve(candidates, "video", null, 3, width, height) is ScreenTargetResolution.NotFound)
    }
}
