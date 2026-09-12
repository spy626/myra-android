package com.myra.assistant.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream

sealed class ScreenPrivacyResult {
    data class Allowed(
        val bytes: ByteArray,
        val categories: Set<String>,
        val regionCount: Int,
        val redactionApplied: Boolean
    ) : ScreenPrivacyResult()
    data class Blocked(val categories: Set<String>, val reason: String) : ScreenPrivacyResult()
}

/** Redacts only sensitive accessibility regions. Public pages and ordinary text remain visible. */
object ScreenFramePrivacyFilter {
    fun apply(
        jpeg: ByteArray,
        elements: List<VisibleScreenElement>,
        screenWidth: Int,
        screenHeight: Int,
        enabled: Boolean
    ): ScreenPrivacyResult {
        if (!enabled) return ScreenPrivacyResult.Allowed(jpeg, emptySet(), 0, false)
        val sensitive = elements.mapNotNull { element ->
            ScreenPrivacyPolicy.sensitiveCategory(element.label)?.let { it to element.bounds }
        }
        if (sensitive.isEmpty()) return ScreenPrivacyResult.Allowed(jpeg, emptySet(), 0, false)
        val bitmap = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inMutable = true }
        )
            ?: return ScreenPrivacyResult.Blocked(sensitive.map { it.first }.toSet(), "unredactable_sensitive_screen")
        if (screenWidth <= 0 || screenHeight <= 0) {
            bitmap.recycle()
            return ScreenPrivacyResult.Blocked(sensitive.map { it.first }.toSet(), "unredactable_sensitive_screen")
        }
        return try {
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
            val sx = bitmap.width.toFloat() / screenWidth
            val sy = bitmap.height.toFloat() / screenHeight
            sensitive.forEach { (_, bounds) ->
                canvas.drawRect(bounds.left * sx, bounds.top * sy, bounds.right * sx, bounds.bottom * sy, paint)
            }
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)) {
                ScreenPrivacyResult.Blocked(sensitive.map { it.first }.toSet(), "redaction_encode_failed")
            } else ScreenPrivacyResult.Allowed(
                output.toByteArray(), sensitive.map { it.first }.toSet(), sensitive.size, true
            )
        } finally { bitmap.recycle() }
    }
}
