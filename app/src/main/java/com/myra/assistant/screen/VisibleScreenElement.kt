package com.myra.assistant.screen

import android.graphics.Rect

data class VisibleScreenElement(
    val label: String,
    val bounds: Rect,
    val clickable: Boolean,
    val className: String
)

