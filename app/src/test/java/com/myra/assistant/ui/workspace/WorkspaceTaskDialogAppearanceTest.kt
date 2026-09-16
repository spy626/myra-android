package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source regression guard; actual contrast and layout still require a phone visual check. */
class WorkspaceTaskDialogAppearanceTest {
    @Test fun taskConfirmationsAndSourceChooserUseReadableLyraPalette() {
        val activity = File("src/main/java/com/myra/assistant/ui/workspace/WorkspaceTaskActivity.kt").readText()
        val background = File("src/main/res/drawable/bg_workspace_dialog.xml").readText()
        // Unsaved guard, task replacement, explicit source chooser and saved-spec approval.
        assertEquals(4, Regex("\\.showTaskConfirmation\\(\\)").findAll(activity).count() - 1)
        assertTrue(activity.contains("setBackgroundDrawableResource(R.drawable.bg_workspace_dialog)"))
        assertTrue(activity.contains("Color.rgb(190, 255, 202)"))
        assertTrue(activity.contains("Color.rgb(255, 190, 180)"))
        assertTrue(activity.contains("setAdapter(adapter)"))
        assertTrue(activity.contains("Color.rgb(217, 243, 222)"))
        assertTrue(activity.contains(".setTitle(\"Approve saved spec for planning?\")"))
        assertTrue(activity.contains("No AI runs, file edits, builds, tool permissions or payments are authorized."))
        assertTrue(background.contains("#07110F"))
    }

    @Test fun sourceChooserDoesNotHideFilesBehindDialogMessage() {
        val activity = File("src/main/java/com/myra/assistant/ui/workspace/WorkspaceTaskActivity.kt").readText()
        val chooser = activity.substringAfter("private fun reviewSource()")
            .substringBefore("private fun showSourcePreview(")
        assertTrue(chooser.contains(".setAdapter(adapter)"))
        // Android's AlertDialog message view takes precedence over the adapter list.
        assertFalse(chooser.contains(".setMessage("))
        assertTrue(chooser.contains(".setTitle(\"Choose a project file (read-only)\")"))
    }
}
