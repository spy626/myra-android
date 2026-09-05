package com.myra.assistant.screen

import android.graphics.Rect

data class VisibleScreenElement(
    val label: String,
    val bounds: Rect,
    val clickable: Boolean,
    val className: String,
    val text: String = label,
    val contentDescription: String = "",
    val hint: String = "",
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val sourceNodeId: String? = null
)

