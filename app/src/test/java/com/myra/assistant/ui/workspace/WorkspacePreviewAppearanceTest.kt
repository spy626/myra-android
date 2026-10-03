package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Protect against reintroducing a dark host canvas under a light website. */
class WorkspacePreviewAppearanceTest {
    @Test fun previewUsesWhiteCanvasAndDoesNotForceDarkenWebsites() {
        val activity = File("src/main/java/com/myra/assistant/ui/workspace/WorkspacePreviewActivity.kt")
            .readText()
        val layout = File("src/main/res/layout/activity_workspace_preview.xml")
            .readText()
        assertTrue(activity.contains("setBackgroundColor(android.graphics.Color.WHITE)"))
        assertTrue(activity.contains("setAlgorithmicDarkeningAllowed(false)"))
        assertTrue(activity.contains("setForceDark(WebSettings.FORCE_DARK_OFF)"))
        assertTrue(Regex("<WebView[\\s\\S]*?android:background=\"#FFFFFF\"").containsMatchIn(layout))
    }
}
