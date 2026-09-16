package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source regression guard; actual contrast and layout still require a phone visual check. */
class WorkspaceTaskDialogAppearanceTest {
    @Test fun bothTaskConfirmationsUseReadableLyraPalette() {
        val activity = File("src/main/java/com/myra/assistant/ui/workspace/WorkspaceTaskActivity.kt")
            .readText()
        val background = File("src/main/res/drawable/bg_workspace_dialog.xml").readText()
        assertEquals(2, Regex("\\.showTaskConfirmation\\(\\)").findAll(activity).count() - 1)
        assertTrue(activity.contains("setBackgroundDrawableResource(R.drawable.bg_workspace_dialog)"))
        assertTrue(activity.contains("Color.rgb(190, 255, 202)"))
        assertTrue(activity.contains("Color.rgb(255, 190, 180)"))
        assertTrue(background.contains("#07110F"))
    }
}
