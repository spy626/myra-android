package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source regression guard; actual contrast and layout still require a phone visual check. */
class WorkspaceTaskDialogAppearanceTest {
    @Test fun taskConfirmationsAndBothFileChoosersUseReadableLyraPalette() {
        val activity = File("src/main/java/com/myra/assistant/ui/workspace/WorkspaceTaskActivity.kt").readText()
        val background = File("src/main/res/drawable/bg_workspace_dialog.xml").readText()
        // Unsaved guard, task replacement, source chooser, spec approval, context chooser, review note.
        assertEquals(6, Regex("\\.showTaskConfirmation\\(\\)").findAll(activity).count() - 1)
        assertTrue(activity.contains("setBackgroundDrawableResource(R.drawable.bg_workspace_dialog)"))
        assertTrue(activity.contains("Color.rgb(190, 255, 202)"))
        assertTrue(activity.contains("Color.rgb(255, 190, 180)"))
        assertEquals(2, Regex("\\.setAdapter\\(adapter\\)").findAll(activity).count())
        assertTrue(activity.contains("Color.rgb(217, 243, 222)"))
        assertTrue(activity.contains(".setTitle(\"Approve saved spec for planning?\")"))
        assertTrue(activity.contains("No AI runs, file edits, builds, tool permissions or payments are authorized."))
        assertTrue(activity.contains("Plan scope note (screen only; not saved)"))
        assertTrue(background.contains("#07110F"))
    }

    @Test fun bothFileChoosersShowListsRatherThanDialogMessages() {
        val activity = File("src/main/java/com/myra/assistant/ui/workspace/WorkspaceTaskActivity.kt").readText()
        val chooser = activity.substringAfter("private fun reviewSource()")
            .substringBefore("private fun showSourcePreview(")
        assertTrue(chooser.contains(".setAdapter(adapter)"))
        assertFalse(chooser.contains(".setMessage("))
        assertTrue(chooser.contains(".setTitle(\"Choose a project file (read-only)\")"))
        val contextChooser = activity.substringAfter("private fun prepareLocalContext()")
            .substringBefore("private fun clearLocalPlan()")
        assertTrue(contextChooser.contains(".setAdapter(adapter)"))
        assertFalse(contextChooser.contains(".setMessage("))
        assertTrue(contextChooser.contains(".setTitle(\"Choose one file for local review (not sent)\")"))
    }

    @Test fun selectedFileDirectlyDisplaysBothLocalDraftsWithoutAnotherPlanTap() {
        val activity = File("src/main/java/com/myra/assistant/ui/workspace/WorkspaceTaskActivity.kt").readText()
        val selected = activity.substringAfter("private fun showLocalContext(")
            .substringBefore("private fun recheckLocalContext()")
        assertTrue(activity.contains("binding.taskContext.text = \"Prepare local review (context + plan)\""))
        assertTrue(selected.contains("WorkspaceLocalReview.prepare("))
        assertTrue(selected.contains("localContext = it.context"))
        assertTrue(selected.contains("localPlan = it.plan"))
        assertTrue(selected.contains("localPlanPreview.text = it.plan.displayText()"))
        assertTrue(selected.contains("localPlanPreview.visibility = View.VISIBLE"))
        assertTrue(selected.contains("localReviewButton.visibility = View.VISIBLE"))
    }
}
