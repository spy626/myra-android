package com.myra.assistant.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceConnectedGitHubReadRoutingTest {
    private fun build(text: String, number: Long): WorkspaceConnectedGitHubReadRouting.Route.Build {
        val decision = WorkspaceConnectedGitHubReadRouting.decide(text)
        assertTrue(text, decision is WorkspaceConnectedGitHubReadRouting.Route.Build)
        val result = decision as WorkspaceConnectedGitHubReadRouting.Route.Build
        assertEquals(text, number, result.decision.runNumber)
        return result
    }

    @Test fun exactNaturalPhonePromptSelectsRunWithoutSayingGitHubOrTool() {
        build("bro 3372 green hai kya check kro", 3372L)
        build("bro #3372 green hai kya check kro", 3372L)
        build("Bhai #3372 fail tha kya dekhna", 3372L)
        build("3372 green hai?", 3372L)
    }

    @Test fun buildStatusAndCommitShaSelectActionsNotBranchHead() {
        val text = "Bro, #3372 build ka abhi ka status check karke batao. Green hai ya fail? " +
            "Uska commit SHA bhi bata dena. Kuch change mat karna, naya build bhi mat chalana."
        build(text, 3372L)
        build("Verify LIVE GitHub Actions Build #3372 status and commit SHA. " +
            "Do not edit files, push commits, or start another build.", 3372L)
    }

    @Test fun genuineBranchHeadLookupRemainsHeadReadEvenWhenBuildIsNegated() {
        val original = "LYRA, use your connected GitHub read capability to fetch the LIVE " +
            "current HEAD commit SHA of agent/myra-phase-1 and main in spy626/myra-android. " +
            "Do not guess from conversation history. Do not modify files, push commits, or " +
            "start a build. If the live read fails, clearly report the failure."
        val route = WorkspaceConnectedGitHubReadRouting.decide(original)
        assertTrue(route is WorkspaceConnectedGitHubReadRouting.Route.Heads)
        val heads = (route as WorkspaceConnectedGitHubReadRouting.Route.Heads).decision
        assertTrue(heads.feature)
        assertTrue(heads.main)

        val negativeRun = WorkspaceConnectedGitHubReadRouting.decide(
            "Show me the LIVE GitHub feature branch HEAD SHA. Do not start build #3372."
        )
        assertTrue(negativeRun is WorkspaceConnectedGitHubReadRouting.Route.Heads)
    }

    @Test fun naturalApkFollowUpTakesPrecedenceWithoutStealingWrites() {
        fun msg(role: String, text: String) =
            WorkspaceConversationStore.Message(role, role, text, 1L)
        val previous = listOf(
            msg("user", "bro 3374 green hai kya check kro"),
            msg("assistant", "Haan bro Build #3374 GREEN hai\n" +
                "[🔗 GitHub Build #3374 ↗](https://github.com/spy626/myra-android/actions/runs/36837049059)")
        )
        val route = WorkspaceConnectedGitHubReadRouting.decide(
            "achha bro iska APK download link bhi bhej do 😂", previous
        )
        assertTrue(route is WorkspaceConnectedGitHubReadRouting.Route.Download)
        assertEquals(3374L, (route as WorkspaceConnectedGitHubReadRouting.Route.Download)
            .decision.runNumber)
        assertNull(WorkspaceConnectedGitHubReadRouting.decide(
            "APK download button fix karo", previous
        ))
    }

    @Test fun ambiguousRunsFailClosedAndWritesAreNotDispatchedAsReads() {
        val ambiguous = WorkspaceConnectedGitHubReadRouting.decide(
            "Check build #3372 and build #3370 status, no changes."
        )
        assertTrue(ambiguous is WorkspaceConnectedGitHubReadRouting.Route.Build)
        assertTrue(
            (ambiguous as WorkspaceConnectedGitHubReadRouting.Route.Build)
                .decision.localError?.contains("one GitHub Actions run at a time") == true
        )
        assertNull(WorkspaceConnectedGitHubReadRouting.decide(
            "GitHub repo me runtime status label fix karo"
        ))
        assertNull(WorkspaceConnectedGitHubReadRouting.decide(
            "bro PR #3372 status check kro"
        ))
    }
}
