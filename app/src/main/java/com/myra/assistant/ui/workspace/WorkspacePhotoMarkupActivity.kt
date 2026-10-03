package com.myra.assistant.ui.workspace

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Local-only photo preview/markup surface.
 *
 * The original provider URI is never modified. Done writes a bounded JPEG copy into LYRA cache,
 * returns only that local FileProvider URI, and leaves provider routing unchanged.
 */
class WorkspacePhotoMarkupActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_RESULT_URI = "result_uri"
        const val EXTRA_RESULT_NAME = "result_name"
        private const val MAX_DECODE_PIXELS = 6_000_000L
    }

    private lateinit var markupView: WorkspacePhotoMarkupView

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + .5f).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun action(
        value: String,
        textColor: Int = Color.rgb(235, 239, 236),
        onClick: () -> Unit,
    ) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(textColor)
        gravity = Gravity.CENTER
        background = rounded(Color.rgb(31, 35, 32), 18)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val source = intent.getStringExtra(EXTRA_IMAGE_URI)?.let(Uri::parse)
        if (source == null) {
            finish()
            return
        }
        val bitmap = runCatching { decodeBounded(source) }.getOrElse {
            Toast.makeText(this, it.message ?: "Photo could not be opened", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 5, 5))
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }

        val top = FrameLayout(this)
        top.addView(
            action("Cancel") { finish() },
            FrameLayout.LayoutParams(dp(76), dp(44), Gravity.START or Gravity.CENTER_VERTICAL),
        )
        top.addView(
            TextView(this).apply {
                text = "Mark photo"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(-1, dp(44), Gravity.CENTER),
        )
        top.addView(
            action("Done", Color.rgb(168, 255, 178)) { saveMarkedPhoto() },
            FrameLayout.LayoutParams(dp(70), dp(44), Gravity.END or Gravity.CENTER_VERTICAL),
        )
        root.addView(top, LinearLayout.LayoutParams(-1, dp(52)))

        markupView = WorkspacePhotoMarkupView(this, bitmap)
        root.addView(markupView, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        })

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tools.addView(
            action("↶  Undo") { markupView.undo() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) },
        )
        tools.addView(
            action("Redo  ↷") { markupView.redo() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) },
        )
        root.addView(tools, LinearLayout.LayoutParams(-1, dp(48)))

        setContentView(root)
    }

    private fun decodeBounded(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Photo cannot be read")
        boundsStream.use {
            // Bounds-only decoding intentionally returns null; outWidth/outHeight carry success.
            BitmapFactory.decodeStream(it, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Photo format is not supported" }

        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) >
            MAX_DECODE_PIXELS) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decodeStream = contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Photo cannot be read")
        return decodeStream.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalArgumentException("Photo cannot be decoded")
    }

    private fun saveMarkedPhoto() {
        val output = runCatching {
            val dir = File(cacheDir, "workspace-photo").apply { mkdirs() }
            require(dir.isDirectory) { "Photo cache is unavailable" }
            val file = File(dir, "marked-${System.currentTimeMillis()}.jpg")
            val rendered = markupView.renderBitmap()
            var quality = 92
            var saved = false
            while (quality >= 52) {
                FileOutputStream(file).use { stream ->
                    require(rendered.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                        "Marked photo could not be saved"
                    }
                }
                if (file.length() in 1L..2_000_000L) {
                    saved = true
                    break
                }
                quality -= 10
            }
            require(saved) { "Marked photo exceeds LYRA's 2 MB image limit" }
            file
        }.getOrElse {
            Toast.makeText(this, it.message ?: "Marked photo could not be saved", Toast.LENGTH_LONG).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            output,
        )
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT_URI, uri.toString())
                .putExtra(EXTRA_RESULT_NAME, output.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        finish()
    }
}

private class WorkspacePhotoMarkupView(
    context: android.content.Context,
    private val bitmap: Bitmap,
) : View(context) {
    private data class Stroke(
        val path: Path,
        val widthOnBitmap: Float,
    )

    private val destination = RectF()
    private val strokes = mutableListOf<Stroke>()
    private val redo = mutableListOf<Stroke>()
    private var current: Stroke? = null
    private var imageScale = 1f

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun updateDestination() {
        if (width <= 0 || height <= 0) return
        imageScale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            .coerceAtLeast(0.0001f)
        val drawWidth = bitmap.width * imageScale
        val drawHeight = bitmap.height * imageScale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        destination.set(left, top, left + drawWidth, top + drawHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDestination()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, null, destination, imagePaint)
        canvas.save()
        canvas.translate(destination.left, destination.top)
        canvas.scale(imageScale, imageScale)
        (strokes + listOfNotNull(current)).forEach { stroke ->
            strokePaint.strokeWidth = stroke.widthOnBitmap
            canvas.drawPath(stroke.path, strokePaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (destination.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!destination.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                val point = toBitmapPoint(event.x, event.y)
                current = Stroke(
                    Path().apply { moveTo(point.first, point.second) },
                    (resources.displayMetrics.density * 6f / imageScale).coerceAtLeast(1f),
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val active = current ?: return false
                val x = event.x.coerceIn(destination.left, destination.right)
                val y = event.y.coerceIn(destination.top, destination.bottom)
                val point = toBitmapPoint(x, y)
                active.path.lineTo(point.first, point.second)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let {
                    strokes.add(it)
                    redo.clear()
                }
                current = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun toBitmapPoint(x: Float, y: Float): Pair<Float, Float> =
        ((x - destination.left) / imageScale).coerceIn(0f, bitmap.width.toFloat()) to
            ((y - destination.top) / imageScale).coerceIn(0f, bitmap.height.toFloat())

    fun undo() {
        if (strokes.isEmpty()) return
        redo.add(strokes.removeAt(strokes.lastIndex))
        invalidate()
    }

    fun redo() {
        if (redo.isEmpty()) return
        strokes.add(redo.removeAt(redo.lastIndex))
        invalidate()
    }

    fun renderBitmap(): Bitmap {
        val output = requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true)) {
            "Photo copy could not be created"
        }
        val canvas = Canvas(output)
        strokes.forEach { stroke ->
            strokePaint.strokeWidth = stroke.widthOnBitmap
            canvas.drawPath(stroke.path, strokePaint)
        }
        current?.let {
            strokePaint.strokeWidth = it.widthOnBitmap
            canvas.drawPath(it.path, strokePaint)
        }
        return output
    }
}
