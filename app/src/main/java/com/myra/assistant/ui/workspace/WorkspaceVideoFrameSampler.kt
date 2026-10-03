package com.myra.assistant.ui.workspace

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Limited, explicit video-to-still-frames bridge for OpenRouter Free image input.
 * This is NOT video/audio understanding. Never transmit the original video bytes.
 */
internal object WorkspaceVideoFrameSampler {
    const val MAX_VIDEO_MS = 5 * 60 * 1000L
    private const val MAX_FRAME_SIDE = 720
    private const val MAX_FRAME_BYTES = 600_000

    /** Three ordered representative positions; bounded pure function for unit tests. */
    fun sampleTimesUs(durationMs: Long): List<Long> {
        require(durationMs in 1..MAX_VIDEO_MS) {
            "Video must be 5 minutes or shorter for three sampled still frames"
        }
        return listOf(10L, 50L, 90L).map { durationMs * 1_000L * it / 100L }
    }

    fun frames(context: Context, uri: Uri): List<WorkspaceChatGateway.Image> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: throw IllegalArgumentException("Video duration is unreadable")
            sampleTimesUs(durationMs).map { timeUs ->
                val frame = retriever.getFrameAtTime(timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IllegalArgumentException("A video sample frame could not be decoded")
                val side = maxOf(frame.width, frame.height)
                val scaled = if (side > MAX_FRAME_SIDE) {
                    val ratio = MAX_FRAME_SIDE.toFloat() / side
                    Bitmap.createScaledBitmap(frame,
                        (frame.width * ratio).toInt().coerceAtLeast(1),
                        (frame.height * ratio).toInt().coerceAtLeast(1), true)
                } else frame
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
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
                require(jpeg.size in 1..MAX_FRAME_BYTES) {
                    "Video frame exceeds LYRA's safe free-route image budget"
                }
                WorkspaceChatGateway.Image("image/jpeg",
                    Base64.encodeToString(jpeg, Base64.NO_WRAP))
            }
        } finally {
            retriever.release()
        }
    }
}
