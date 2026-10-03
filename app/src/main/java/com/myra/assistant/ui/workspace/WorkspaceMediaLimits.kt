package com.myra.assistant.ui.workspace

/** Free-only attachment budgets: keep mobile memory/upload predictable. */
internal object WorkspaceMediaLimits {
    const val MAX_PHOTOS = 10
    const val MAX_PHOTO_BYTES = 950_000
    const val MAX_IMAGE_BASE64_TOTAL = 13_000_000
    const val MAX_NATIVE_VIDEO_BYTES = 12_000_000
    const val MAX_NATIVE_VIDEO_BASE64 = 16_000_000
    const val MAX_AUDIO_BYTES = 8_000_000
    const val MAX_AUDIO_BASE64 = 10_666_672
    const val MAX_VIDEO_DURATION_MS = 5 * 60 * 1000L

    fun canAddPhoto(currentPhotos: Int, totalAttachments: Int): Boolean =
        currentPhotos in 0 until MAX_PHOTOS && totalAttachments in 0 until MAX_PHOTOS

    fun imageEnvelopeSizes(lengths: List<Int>): Boolean =
        lengths.size in 1..MAX_PHOTOS &&
            lengths.all { it in 1..1_270_000 } &&
            lengths.fold(0L) { total, size -> total + size } <= MAX_IMAGE_BASE64_TOTAL
}
