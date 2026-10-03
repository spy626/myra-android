package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceCodingRoleRouterTest {
    @Test fun quickEditUsesGroqWhenPermitted() {
        assertEquals(WorkspaceCodingRoleRouter.Provider.GROQ,
            WorkspaceCodingRoleRouter.select("GitHub button text fix karo", 2400, true, true))
    }

    @Test fun heavyEditUsesXKiroWhenPermitted() {
        assertEquals(WorkspaceCodingRoleRouter.Provider.XKIRO,
            WorkspaceCodingRoleRouter.select("Refactor checkpoint fallback state machine", 2000, true, true))
        assertEquals(WorkspaceCodingRoleRouter.Provider.XKIRO,
            WorkspaceCodingRoleRouter.select("Fix this file", 8000, true, true))
    }

    @Test fun permissionIsNeverInvented() {
        assertEquals(WorkspaceCodingRoleRouter.Provider.XKIRO,
            WorkspaceCodingRoleRouter.select("Small edit", 500, true, false))
        assertEquals(WorkspaceCodingRoleRouter.Provider.GROQ,
            WorkspaceCodingRoleRouter.select("Architecture refactor", 9000, false, true))
        assertNull(WorkspaceCodingRoleRouter.select("Small edit", 500, false, false))
    }
}
