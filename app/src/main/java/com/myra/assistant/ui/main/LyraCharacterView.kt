package com.myra.assistant.ui.main

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.myra.assistant.R
import kotlin.math.abs

/**
 * Reliable Media3-backed LYRA idle animation.
 *
 * The existing class name and tap callback are preserved so the activity and
 * layout remain unchanged. Playback is always muted and loops locally.
 */
class LyraCharacterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : PlayerView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAP_SLOP_PX = 18f
        private const val TAP_COOLDOWN_MS = 5_000L
    }

    private var exoPlayer: ExoPlayer? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapAt = 0L

    var onCharacterTapped: (() -> Unit)? = null

    init {
        isClickable = true
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        setShutterBackgroundColor(Color.TRANSPARENT)
        setKeepContentOnPlayerReset(true)

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                }
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> moved = true
                MotionEvent.ACTION_MOVE -> {
                    if (
                        event.pointerCount > 1 ||
                        abs(event.x - downX) > TAP_SLOP_PX ||
                        abs(event.y - downY) > TAP_SLOP_PX
                    ) {
                        moved = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastTapAt >= TAP_COOLDOWN_MS) {
                            lastTapAt = now
                            performClick()
                            onCharacterTapped?.invoke()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> moved = true
            }
            true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPlayer()
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }

    private fun startPlayer() {
        if (exoPlayer != null) return

        val videoUri = "android.resource://" + context.packageName + "/" + R.raw.lyra_idle
        val newPlayer = ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
        exoPlayer = newPlayer
        player = newPlayer
    }

    private fun releasePlayer() {
        player = null
        exoPlayer?.release()
        exoPlayer = null
    }
}
