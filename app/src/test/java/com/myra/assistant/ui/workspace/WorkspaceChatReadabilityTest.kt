package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class WorkspaceChatReadabilityTest {
    @Test fun chatTextUsesReadableNativeSpWithoutChangingWorkTimeline() {
        val reply = WorkspaceChatReadability.assistant
        val mine = WorkspaceChatReadability.user
        assertEquals(17f, reply.fontSp, 0f)
        assertEquals(16f, mine.fontSp, 0f)
        assertTrue(reply.lineMultiplier > 1f)
        assertTrue(mine.lineMultiplier > 1f)
        assertTrue(reply.extraLineDp >= mine.extraLineDp)
        assertTrue(reply.maxWidthGutterDp < mine.maxWidthGutterDp)
        assertTrue(reply.horizontalPaddingDp < mine.horizontalPaddingDp)
        assertTrue(reply.verticalPaddingDp > mine.verticalPaddingDp)
        assertEquals(56, mine.maxWidthGutterDp)
        assertEquals(12, mine.horizontalPaddingDp)
        assertEquals(8, mine.verticalPaddingDp)
        assertEquals(0, mine.extraLineDp)
        assertEquals(3, reply.extraLineDp)
    }

    @Test fun verifiedLinkAccentContrastsWithDarkChatBackground() {
        fun luminance(color: Int): Double {
            val channels = intArrayOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255)
            fun linear(value: Int): Double {
                val srgb = value / 255.0
                return if (srgb <= 0.04045) srgb / 12.92
                    else ((srgb + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(channels[0]) +
                0.7152 * linear(channels[1]) + 0.0722 * linear(channels[2])
        }
        val foreground = luminance(WorkspaceChatReadability.verifiedLinkColor)
        val darkBackground = luminance(0xFF070D0A.toInt())
        assertTrue((foreground + 0.05) / (darkBackground + 0.05) >= 4.5)
    }
}
