package com.myra.assistant.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTaskDraftPolicyTest {
    @Test fun newBriefIsUnsavedUntilPersisted() {
        assertFalse(WorkspaceTaskDraftPolicy.isDirty("", null))
        assertTrue(WorkspaceTaskDraftPolicy.isDirty("Build a website", null))
        assertFalse(WorkspaceTaskDraftPolicy.isDirty("Build a website", "Build a website"))
    }

    @Test fun editsAndClearingAnExistingBriefRemainUnsaved() {
        assertTrue(WorkspaceTaskDraftPolicy.isDirty("Build a better website", "Build a website"))
        assertTrue(WorkspaceTaskDraftPolicy.isDirty("", "Build a website"))
        assertTrue(WorkspaceTaskDraftPolicy.isDirty("Build a website ", "Build a website"))
        assertFalse(WorkspaceTaskDraftPolicy.isDirty("Build a website", "Build a website"))
    }

    @Test fun criteriaOnlyEditsCannotBypassNavigationGuard() {
        assertTrue(WorkspaceTaskDraftPolicy.isDirty("Same goal", "Same goal", "New criteria", "Old criteria"))
        assertTrue(WorkspaceTaskDraftPolicy.isDirty("Same goal", "Same goal", "", "Old criteria"))
        assertTrue(WorkspaceTaskDraftPolicy.isDirty("Same goal", "Same goal", "New criteria", null))
        assertFalse(WorkspaceTaskDraftPolicy.isDirty("Same goal", "Same goal", "Expected result", "Expected result"))
    }
}
