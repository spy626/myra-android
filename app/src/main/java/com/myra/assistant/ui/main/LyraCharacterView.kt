package com.myra.assistant.ui.main

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import com.google.android.filament.View
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline GLB character renderer with character-only yaw and bounded zoom.
 * Camera orbit is intentionally disabled so LYRA cannot flip sideways.
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
        private const val TAP_COOLDOWN_MS = 5_000L
        private const val DOUBLE_TAP_MS = 320L
        private const val MIN_SCALE = 0.82f
        private const val MAX_SCALE = 1.55f
    }

    private val choreographer = Choreographer.getInstance()
    private val modelViewer = ModelViewer(this)
    private var rendering = false
    private var baseRootTransform: FloatArray? = null
    private var yawRadians = 0f
    private var characterScale = 1.08f
    private var downX = 0f
    private var downY = 0f
    private var previousX = 0f
    private var moved = false
    private var pinching = false
    private var pinchDistance = 0f
    private var lastTapAt = 0L
    private var lastReleaseAt = 0L
    private var pendingTap: Runnable? = null
    var onCharacterTapped: (() -> Unit)? = null

    init {
        isClickable = true
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
        modelViewer.scene.skybox = null
        loadBundledModel()

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    previousX = event.x
                    moved = false
                    pinching = false
                    pinchDistance = 0f
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    moved = true
                    pinching = true
                    pinchDistance = pointerDistance(event)
                    pendingTap?.let(::removeCallbacks)
                    pendingTap = null
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        moved = true
                        pinching = true
                        val distance = pointerDistance(event)
                        if (pinchDistance > 0f && distance > 0f) {
                            characterScale = (characterScale * distance / pinchDistance)
                                .coerceIn(MIN_SCALE, MAX_SCALE)
                            applyRootTransform()
                        }
                        pinchDistance = distance
                    } else if (!pinching) {
                        val dx = event.x - previousX
                        if (kotlin.math.abs(event.x - downX) > TAP_SLOP_PX ||
                            kotlin.math.abs(event.y - downY) > TAP_SLOP_PX
                        ) moved = true
                        if (moved) {
                            yawRadians += dx * 0.0105f
                            applyRootTransform()
                        }
                        previousX = event.x
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    moved = true
                    pinching = true
                    pinchDistance = 0f
                }
                MotionEvent.ACTION_UP -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!moved && !pinching) {
                        if (now - lastReleaseAt <= DOUBLE_TAP_MS) {
                            pendingTap?.let(::removeCallbacks)
                            pendingTap = null
                            resetView()
                            lastReleaseAt = 0L
                        } else {
                            lastReleaseAt = now
                            val tap = Runnable {
                                val current = android.os.SystemClock.elapsedRealtime()
                                if (current - lastTapAt >= TAP_COOLDOWN_MS) {
                                    lastTapAt = current
                                    performClick()
                                    onCharacterTapped?.invoke()
                                }
                                pendingTap = null
                            }
                            pendingTap = tap
                            postDelayed(tap, DOUBLE_TAP_MS)
                        }
                    }
                    pinching = false
                    pinchDistance = 0f
                }
                MotionEvent.ACTION_CANCEL -> {
                    pinching = false
                    pinchDistance = 0f
                }
            }
            true
        }
    }

    private fun loadBundledModel() {
        runCatching {
            val data = context.assets.open(MODEL_ASSET).use { ByteBuffer.wrap(it.readBytes()) }
            modelViewer.loadModelGlb(data)
            modelViewer.transformToUnitCube()
            val asset = modelViewer.asset ?: error("LYRA model asset missing")
            val tm = modelViewer.engine.transformManager
            baseRootTransform = tm.getTransform(tm.getInstance(asset.root), null)
            applyRelaxedPose()
            applyRootTransform()
        }.onFailure {
            visibility = GONE
        }
    }

    private fun applyRelaxedPose() {
        // Bring the upper arms closer to the body. This is a static rest pose;
        // the asset has a skeleton but no embedded animation clips.
        rotateNamedBone("upperarm.l_045", -24f)
        rotateNamedBone("upperarm.r_064", 24f)
        rotateNamedBone("lowerarm.l_046", -7f)
        rotateNamedBone("lowerarm.r_065", 7f)
    }

    private fun rotateNamedBone(name: String, degrees: Float) {
        val asset = modelViewer.asset ?: return
        val entity = asset.getFirstEntityByName(name)
        if (entity == 0) return
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(entity)
        val original = tm.getTransform(instance, null)
        tm.setTransform(instance, multiply(original, rotationZ(degrees * PI.toFloat() / 180f)))
    }

    private fun applyRootTransform() {
        val asset = modelViewer.asset ?: return
        val base = baseRootTransform ?: return
        val user = multiply(rotationY(yawRadians), uniformScale(characterScale))
        val tm = modelViewer.engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), multiply(user, base))
    }

    private fun resetView() {
        yawRadians = 0f
        characterScale = 1.08f
        applyRootTransform()
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return sqrt(dx * dx + dy * dy)
    }

    private fun rotationY(angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
    }

    private fun rotationZ(angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        return floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }

    private fun uniformScale(value: Float) = floatArrayOf(
        value, 0f, 0f, 0f,
        0f, value, 0f, 0f,
        0f, 0f, value, 0f,
        0f, 0f, 0f, 1f
    )

    private fun multiply(left: FloatArray, right: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (column in 0..3) {
            for (row in 0..3) {
                var value = 0f
                for (k in 0..3) value += left[k * 4 + row] * right[column * 4 + k]
                result[column * 4 + row] = value
            }
        }
        return result
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
        pendingTap?.let(::removeCallbacks)
        pendingTap = null
        choreographer.removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!rendering) return
        choreographer.postFrameCallback(this)
        modelViewer.render(frameTimeNanos)
    }
}
