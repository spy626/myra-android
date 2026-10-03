package com.myra.assistant.ui.workspace

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** SILENT opt-in local visual sampling; never claims continuous video or sound understanding. */
internal object WorkspaceVideoFrameSampler {
    const val MAX_VIDEO_MS = WorkspaceMediaLimits.MAX_VIDEO_DURATION_MS
    const val MAX_FRAMES = 10
    private const val MAX_FRAME_SIDE = 720
    private const val MAX_FRAME_BYTES = 600_000

    /** Bin midpoints across playback: 5%, 15%, ..., 95%, in order. */
    fun sampleTimesUs(durationMs: Long): List<Long> {
        require(durationMs in 1..MAX_VIDEO_MS) {
            "Video must be five minutes or shorter for ten sampled frames"
        }
        return (0 until MAX_FRAMES).map { index ->
            durationMs * 1_000L * (2L * index + 1L) / (2L * MAX_FRAMES)
        }
    }

    fun frames(context: Context, uri: Uri): List<WorkspaceChatGateway.Image> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: throw IllegalArgumentException("Video duration is unreadable")
            sampleTimesUs(durationMs).map { timeUs ->
                // Closest sync frame is approximate, not a guarantee of exact timing.
                val frame = retriever.getFrameAtTime(timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IllegalArgumentException("A video sample frame could not be decoded")
                try {
                    val side = maxOf(frame.width, frame.height)
                    val scaled = if (side > MAX_FRAME_SIDE) {
                        val ratio = MAX_FRAME_SIDE.toFloat() / side
                        Bitmap.createScaledBitmap(frame,
                            (frame.width * ratio).toInt().coerceAtLeast(1),
                            (frame.height * ratio).toInt().coerceAtLeast(1), true)
                    } else frame
                    try {
                        val output = ByteArrayOutputStream()
                        var jpeg = byteArrayOf()
                        for (quality in listOf(78, 65, 52)) {
                            output.reset()
                            require(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                                "Video sample frame could not be encoded"
                            }
                            jpeg = output.toByteArray()
                            if (jpeg.size in 1..MAX_FRAME_BYTES) break
                        }
                        require(jpeg.size in 1..MAX_FRAME_BYTES) {
                            "Video frame exceeds LYRA's safe Free image budget"
                        }
                        WorkspaceChatGateway.Image("image/jpeg",
                            Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    } finally {
                        if (scaled !== frame) scaled.recycle()
                    }
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            retriever.release()
        }
    }
}
