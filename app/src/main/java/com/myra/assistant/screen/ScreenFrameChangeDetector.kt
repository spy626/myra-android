package com.myra.assistant.screen

import android.graphics.Bitmap

class ScreenFrameChangeDetector(private val threshold: Double = 0.08) {
    private var previous: IntArray? = null

    fun changed(bitmap: Bitmap): Boolean {
        val sample = sample(bitmap)
        val old = previous
        previous = sample
        if (old == null || old.size != sample.size) return true
        var total = 0.0
        sample.indices.forEach { index ->
            val a = old[index]
            val b = sample[index]
            total += kotlin.math.abs((a shr 16 and 255) - (b shr 16 and 255))
            total += kotlin.math.abs((a shr 8 and 255) - (b shr 8 and 255))
            total += kotlin.math.abs((a and 255) - (b and 255))
        }
        return total / (sample.size * 3.0 * 255.0) >= threshold
    }

    fun reset() { previous = null }

    private fun sample(bitmap: Bitmap): IntArray {
        val width = 16
        val height = 28
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try { IntArray(width * height).also { scaled.getPixels(it, 0, width, 0, 0, width, height) } }
        finally { if (scaled !== bitmap) scaled.recycle() }
    }
}

