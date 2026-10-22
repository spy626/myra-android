package com.myra.assistant.video

/**
 * Slice 2: an honest text-only presentation of the deterministic storyboard.
 * This is intentionally not an AI story writer, renderer, project save, or MP4 export.
 */
internal object VideoStudioPreviewFormatter {
    fun format(draft: VideoStudioDraft): String {
        val kind = when (draft.kind) {
            VideoStudioKind.CONSTRUCTION -> "Miniature construction"
            VideoStudioKind.CARTOON -> "Original cartoon"
            VideoStudioKind.EXPLAINER -> "3D explainer"
        }
        val scenes = VideoStudioPlanning.draftBeats(draft)
        return buildString {
            appendLine("STORYBOARD DRAFT — NOT A VIDEO")
            appendLine()
            appendLine("Brief: ${draft.prompt}")
            appendLine("Mode: $kind")
            appendLine("Duration: ${draft.durationSeconds} seconds")
            appendLine("Format: ${draft.aspectRatio} vertical")
            appendLine()
            appendLine("Suggested scenes")
            scenes.forEachIndexed { index, beat ->
                appendLine("${index + 1}. ${beat.startSecond}–${beat.endSecond}s  ${beat.purpose}")
            }
            appendLine()
            appendLine("Template timing only. No AI scene design, 3D assets,")
            appendLine("rendering, audio, or MP4 file has been created.")
        }.trimEnd()
    }
}
