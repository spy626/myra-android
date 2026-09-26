package com.myra.assistant.screen

import java.util.Locale

data class VisionCoordinateDecision(
    val allowed: Boolean,
    val reason: String
)

/**
 * Safety contract for the last-resort visual coordinate path.
 * Accessibility remains primary. A raw gesture is allowed only for a fresh, high-confidence,
 * user-authorized visual action on a non-sensitive foreground screen.
 */
object VisionCoordinateSafetyPolicy {
    const val NORMALIZED_MAX = 1000
    const val MIN_CONFIDENCE = 0.86
    const val MAX_FRAME_AGE_MS = 4_000L

    private val blockedPackages = listOf(
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    private val sensitiveSignal = Regex(
        "(?i)\\b(?:password|passcode|otp|one[ -]?time password|verification code|pin|cvv|" +
            "bank|upi|pay(?:ment)?|purchase|checkout|place order|confirm order|send money|transfer|" +
            "delete|remove|erase|factory reset|permission|allow|deny|grant|install|uninstall|" +
            "send|post|publish|submit|confirm|location access|camera access|microphone access|" +
            "security code|recovery code|api[ _-]?key|private key|seed phrase)\\b"
    )

    fun evaluate(
        packageName: String,
        normalizedX: Int,
        normalizedY: Int,
        confidence: Double,
        frameAgeMs: Long,
        visibleLabels: List<String>
    ): VisionCoordinateDecision {
        if (packageName.isBlank()) return VisionCoordinateDecision(false, "missing_foreground")
        val lowerPackage = packageName.lowercase(Locale.ROOT)
        if (blockedPackages.any { lowerPackage == it || lowerPackage.startsWith("$it.") }) {
            return VisionCoordinateDecision(false, "protected_system_surface")
        }
        if (normalizedX !in 10..990 || normalizedY !in 20..985) {
            return VisionCoordinateDecision(false, "unsafe_screen_edge")
        }
        if (confidence < MIN_CONFIDENCE) return VisionCoordinateDecision(false, "low_visual_confidence")
        if (frameAgeMs !in 0..MAX_FRAME_AGE_MS) return VisionCoordinateDecision(false, "stale_visual_frame")
        val sensitive = visibleLabels.any { sensitiveSignal.containsMatchIn(it) }
        if (sensitive) return VisionCoordinateDecision(false, "sensitive_or_consequential_ui")
        return VisionCoordinateDecision(true, "allowed")
    }

    fun toPixel(normalized: Int, size: Int): Float =
        (normalized.coerceIn(0, NORMALIZED_MAX) / NORMALIZED_MAX.toFloat() * size.coerceAtLeast(1))
            .coerceIn(1f, (size - 2).coerceAtLeast(1).toFloat())
}
