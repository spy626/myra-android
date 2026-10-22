package com.myra.assistant.video

/**
 * Slice 1: local, deterministic video-request planning.
 *
 * This model describes a proposed 30/60-second vertical short. It does NOT generate
 * characters, call an AI/video provider, render frames, or claim an MP4 exists.
 * It is intentionally independent of Workspace routing and AIRI memory.
 */
internal enum class VideoStudioKind {
    CARTOON,
    EXPLAINER,
    CONSTRUCTION,
}

/** Characters are designed afresh for a video project, not kept as a permanent cast. */
internal enum class VideoStudioCharacterPolicy {
    NEW_FOR_EACH_PROJECT,
}

internal data class VideoStudioDraft(
    val projectId: String,
    val prompt: String,
    val kind: VideoStudioKind,
    val durationSeconds: Int,
    val aspectRatio: String = "9:16",
    val characterPolicy: VideoStudioCharacterPolicy =
        VideoStudioCharacterPolicy.NEW_FOR_EACH_PROJECT,
)

internal data class VideoStudioBeat(
    val startSecond: Int,
    val endSecond: Int,
    val purpose: String,
) {
    init {
        require(startSecond >= 0 && endSecond > startSecond)
        require(purpose.isNotBlank())
    }
}

internal object VideoStudioPlanning {
    private val safeProjectId = Regex("^[A-Za-z0-9_-]{1,80}$")
    private const val MAX_PROMPT_LENGTH = 1_500

    fun createDraft(
        projectId: String,
        rawPrompt: String,
        kind: VideoStudioKind,
        durationSeconds: Int,
    ): VideoStudioDraft {
        require(safeProjectId.matches(projectId)) { "A valid project identity is required" }
        val prompt = rawPrompt.trim().replace(Regex("\\s+"), " ")
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_LENGTH &&
            prompt.none { it.isISOControl() }) { "A short, readable video brief is required" }
        require(durationSeconds == 30 || durationSeconds == 60) {
            "This first slice supports 30- and 60-second Shorts only"
        }
        return VideoStudioDraft(
            projectId = projectId,
            prompt = prompt,
            kind = kind,
            durationSeconds = durationSeconds,
        )
    }

    /**
     * Template beats only. These labels are examples for human review, not a
     * semantic scene understanding result, verified 3D assets, or a render plan.
     */
    fun draftBeats(draft: VideoStudioDraft): List<VideoStudioBeat> {
        val (cuts, purposes) = when (draft.kind) {
            VideoStudioKind.CONSTRUCTION -> if (draft.durationSeconds == 30) {
                listOf(0, 4, 10, 17, 24, 30) to listOf(
                    "Show the intended finished build",
                    "Establish site and foundation",
                    "Assemble the structural frame and walls",
                    "Add roof, windows, and finishes",
                    "Reveal the completed build",
                )
            } else {
                listOf(0, 8, 19, 31, 43, 52, 60) to listOf(
                    "Show the intended finished build",
                    "Prepare the site and foundation",
                    "Assemble pillars and lower walls",
                    "Build upper structure and roof",
                    "Add details, paint, and lighting",
                    "Reveal the completed build",
                )
            }
            VideoStudioKind.CARTOON -> if (draft.durationSeconds == 30) {
                listOf(0, 3, 8, 16, 24, 30) to listOf(
                    "Open on an expressive visual hook",
                    "Introduce the new characters and goal",
                    "Stage the interaction and comic setup",
                    "Escalate the physical gag",
                    "Deliver the visual punchline",
                )
            } else {
                listOf(0, 6, 16, 32, 47, 60) to listOf(
                    "Open on an expressive visual hook",
                    "Introduce the new characters and goal",
                    "Stage the interaction and comic setup",
                    "Escalate the physical gag",
                    "Deliver the visual punchline",
                )
            }
            VideoStudioKind.EXPLAINER -> if (draft.durationSeconds == 30) {
                listOf(0, 3, 9, 18, 25, 30) to listOf(
                    "Open with the question or surprising fact",
                    "Introduce the key visual object",
                    "Show how the mechanism works",
                    "Explain the main insight",
                    "Deliver the closing reveal",
                )
            } else {
                listOf(0, 6, 17, 36, 49, 60) to listOf(
                    "Open with the question or surprising fact",
                    "Introduce the key visual object",
                    "Show how the mechanism works",
                    "Explain the main insight",
                    "Deliver the closing reveal",
                )
            }
        }
        check(purposes.size == cuts.size - 1 && cuts.first() == 0 &&
            cuts.last() == draft.durationSeconds)
        return purposes.mapIndexed { index, purpose ->
            VideoStudioBeat(cuts[index], cuts[index + 1], purpose)
        }
    }
}
