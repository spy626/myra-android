package com.myra.assistant.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCodeCardStyleTest {
    @Test fun codeTextIsReadableOnACompactPhone() {
        assertTrue(WorkspaceCodeCardStyle.SOURCE_TEXT_SP >= 15f)
        assertTrue(WorkspaceCodeCardStyle.SOURCE_LINE_EXTRA_DP >= 2)
    }

    @Test fun previewAndCopyHaveAccessibleTouchHeight() {
        assertTrue(WorkspaceCodeCardStyle.ACTION_MIN_HEIGHT_DP >= 44)
        assertTrue(WorkspaceCodeCardStyle.HEADER_HEIGHT_DP >= WorkspaceCodeCardStyle.ACTION_MIN_HEIGHT_DP)
    }
}
