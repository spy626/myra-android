package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Small, self-contained launcher for the Workspace surface.
 *
 * Keeping the navigation behavior inside this view lets Phase 1 add Workspace
 * without changing MainActivity's voice, memory, screen-vision, or chat logic.
 */
class WorkspaceEntryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            context.startActivity(Intent(context, WorkspaceActivity::class.java))
        }
    }
}
