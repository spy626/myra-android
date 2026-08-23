package com.myra.assistant.ui.main

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs

/**
 * Displays LYRA's silent idle animation as a hardware-accelerated looping video.
 *
 * The class name and tap callback are intentionally preserved so MainActivity
 * and the existing layout do not need risky changes.
 */
class LyraCharacterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAP_SLOP_PX = 18f
        private const val TAP_COOLDOWN_MS = 5_000L
    }

    private var player: MediaPlayer? = null
    private var playbackSurface: Surface? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapAt = 0L

    var onCharacterTapped: (() -> Unit)? = null

    init {
        isClickable = true
        isOpaque = true
        surfaceTextureListener = this

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

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        startVideo(surfaceTexture)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        updateVideoTransform()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isAvailable && player == null) {
            surfaceTexture?.let(::startVideo)
        }
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }

    private fun startVideo(surfaceTexture: SurfaceTexture) {
        releasePlayer()
        val surface = Surface(surfaceTexture)
        playbackSurface = surface

        runCatching {
            val descriptor = resources.openRawResourceFd(com.myra.assistant.R.raw.lyra_idle)
            MediaPlayer().also { mediaPlayer ->
                player = mediaPlayer
                mediaPlayer.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length
                )
                descriptor.close()
                mediaPlayer.setSurface(surface)
                mediaPlayer.isLooping = true
                mediaPlayer.setVolume(0f, 0f)
                mediaPlayer.setOnVideoSizeChangedListener { _, _, _ -> updateVideoTransform() }
                mediaPlayer.setOnPreparedListener {
                    updateVideoTransform()
                    it.start()
                }
                mediaPlayer.prepareAsync()
            }
        }.onFailure {
            releasePlayer()
            visibility = GONE
        }
    }

    private fun updateVideoTransform() {
        val mediaPlayer = player ?: return
        val videoWidth = mediaPlayer.videoWidth
        val videoHeight = mediaPlayer.videoHeight
        if (width == 0 || height == 0 || videoWidth == 0 || videoHeight == 0) return

        val viewRatio = width.toFloat() / height.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val scaleX: Float
        val scaleY: Float

        if (videoRatio > viewRatio) {
            scaleX = videoRatio / viewRatio
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewRatio / videoRatio
        }

        setTransform(Matrix().apply {
            setScale(scaleX, scaleY, width / 2f, height / 2f)
        })
    }

    private fun releasePlayer() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        playbackSurface?.release()
        playbackSurface = null
    }
}
