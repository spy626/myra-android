package com.myra.assistant.ui.workspace

/**
 * Chat-only native Android typography tokens (sp follows the user's font scale).
 * Work event/status timeline and code-card controls deliberately keep their own sizing.
 */
internal object WorkspaceChatReadability {
    data class Style(
        val fontSp: Float,
        val lineMultiplier: Float,
        val extraLineDp: Int,
        val maxWidthGutterDp: Int,
        val horizontalPaddingDp: Int,
        val verticalPaddingDp: Int,
    )

    val user = Style(
        fontSp = 16f,
        lineMultiplier = 1.04f,
        extraLineDp = 0,
        maxWidthGutterDp = 56,
        horizontalPaddingDp = 12,
        verticalPaddingDp = 8,
    )

    val assistant = Style(
        fontSp = 17f,
        lineMultiplier = 1.08f,
        extraLineDp = 3,
        maxWidthGutterDp = 32,
        horizontalPaddingDp = 10,
        verticalPaddingDp = 11,
    )

    // Light mint contrasts with the near-black chat background; avoids theme-default dark red.
    val verifiedLinkColor: Int = 0xFF9CE8BC.toInt()
}
