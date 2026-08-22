package com.myra.assistant.ui.main

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import com.google.android.filament.View
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Lifecycle-safe, offline GLB viewer for LYRA's selected character.
 *
 * The first character has a rig but no embedded animation clips. ModelViewer's
 * orbit manipulator provides touch-controlled 360-degree inspection while
 * leaving the model data unchanged for later animation upgrades.
 */
class LyraCharacterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    companion object {
        init { Utils.init() }
        private const val MODEL_ASSET = "models/lyra_elf_1k.glb"
        private const val TAP_SLOP_PX = 18f
        private const val TAP_COOLDOWN_MS = 2_500L
    }

    private val choreographer = Choreographer.getInstance()
    private val modelViewer = ModelViewer(this)
    private var rendering = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTapAt = 0L
    var onCharacterTapped: (() -> Unit)? = null

    init {
        isClickable = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
        modelViewer.scene.skybox = null
        loadBundledModel()

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > TAP_SLOP_PX || abs(event.y - downY) > TAP_SLOP_PX) {
                        moved = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        performClick()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastTapAt >= TAP_COOLDOWN_MS) {
                            lastTapAt = now
                            onCharacterTapped?.invoke()
                        }
                    }
                }
            }

            // Keep one-finger character inspection horizontal so LYRA cannot flip
            // sideways or upside-down. Multi-touch remains available for zoom.
            if (event.pointerCount == 1) {
                MotionEvent.obtain(event).also { constrained ->
                    constrained.setLocation(event.x, downY)
                    modelViewer.onTouchEvent(constrained)
                    constrained.recycle()
                }
            } else {
                modelViewer.onTouchEvent(event)
            }
            true
        }
    }

    private fun loadBundledModel() {
        runCatching {
            val data = context.assets.open(MODEL_ASSET).use { ByteBuffer.wrap(it.readBytes()) }
            modelViewer.loadModelGlb(data)
            modelViewer.transformToUnitCube()
        }.onFailure {
            visibility = GONE
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rendering = true
        choreographer.postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        rendering = false
        choreographer.removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!rendering) return
        choreographer.postFrameCallback(this)
        modelViewer.render(frameTimeNanos)
    }
}
