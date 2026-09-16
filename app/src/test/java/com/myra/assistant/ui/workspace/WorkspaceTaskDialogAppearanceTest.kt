package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source regression guard; actual contrast and layout still require a phone visual check. */
class WorkspaceTaskDialogAppearanceTest {
    @Test fun allTaskConfirmationsAndSourceChooserUseReadableLyraPalette() {
        val activity = File("src/main/java/com/myra/assistant/ui/workspace/WorkspaceTaskActivity.kt")
            .readText()
        val background = File("src/main/res/drawable/bg_workspace_dialog.xml").readText()
        // Unsaved-brief guard, task replacement, and explicit read-only source chooser.
        assertEquals(3, Regex("\\.showTaskConfirmation\\(\\)").findAll(activity).count() - 1)
        assertTrue(activity.contains("setBackgroundDrawableResource(R.drawable.bg_workspace_dialog)"))
        assertTrue(activity.contains("Color.rgb(190, 255, 202)"))
        assertTrue(activity.contains("Color.rgb(255, 190, 180)"))
        assertTrue(activity.contains("setAdapter(adapter)"))
        assertTrue(activity.contains("Color.rgb(217, 243, 222)"))
        assertTrue(background.contains("#07110F"))
    }
}
