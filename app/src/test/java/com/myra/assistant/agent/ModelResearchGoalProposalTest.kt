package com.myra.assistant.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelResearchGoalProposalTest {
    private fun authorized(allowed: Boolean = true) = AgentTurnDecision(
        TurnIntent.MULTI_STEP_GOAL, "search", authorizesPhoneActions = allowed, confidence = .95
    )
    private fun proposal(turn: Long, source: String, query: String) =
        ModelResearchGoalProposal(turn, source, query, .96)

    @Test fun approvedModelMayRemoveOnlyAnswerFormattingTail() {
        val final = "Search Android security update and summarize it"
        val accepted = ModelResearchGoalProposal.accept(
            proposal(33, final, "Android security update"), final, 33, authorized())
        assertEquals("Android security update", accepted?.request?.query)
        assertNull(accepted?.request?.explicitDestination)
    }

    @Test fun finalUserSpecifiedDestinationIsAlwaysPreserved() {
        val final = "YouTube search retro music and explain it"
        val accepted = ModelResearchGoalProposal.accept(
            proposal(4, final, "retro music"), final, 4, authorized())
        assertEquals(SearchDestination.YOUTUBE, accepted?.request?.explicitDestination)
        assertEquals("retro music", accepted?.request?.query)
    }

    @Test fun modelCannotPromoteConversationOrStaleTurnOrInventUnrelatedQuery() {
        val final = "Search Android security update and summarize it"
        val candidate = proposal(33, final, "Android security update")
        assertNull(ModelResearchGoalProposal.accept(candidate, final, 33, authorized(false)))
        assertNull(ModelResearchGoalProposal.accept(candidate, final, 33,
            AgentTurnDecision(TurnIntent.CONVERSATION, final, confidence = .99)))
        assertNull(ModelResearchGoalProposal.accept(candidate, final, 34, authorized()))
        assertNull(ModelResearchGoalProposal.accept(
            proposal(33, final, "weather forecast"), final, 33, authorized()))
    }

    @Test fun changedFinalTranscriptNegatedTopicAndPartialSourceFailClosed() {
        val final = "Search Android security update and summarize it"
        assertNull(ModelResearchGoalProposal.accept(proposal(33, final, "Android security update"),
            final + " but do not search", 33, authorized()))
        assertNull(ModelResearchGoalProposal.accept(
            proposal(33, "Search Android security update", "Android security update"),
            final, 33, authorized()))
        val contrast = "Search not Android but iOS"
        assertNull(ModelResearchGoalProposal.accept(
            proposal(33, contrast, "Android"), contrast, 33, authorized()))
    }

    @Test fun sensitiveSearchAndUntrustedOrMalformedToolSchemaFailClosed() {
        val final = "Search my API key and summarize it"
        assertNull(ModelResearchGoalProposal.accept(
            proposal(11, final, "my API key"), final, 11, authorized()))
        val valid = JSONObject().put("kind", "READ_ONLY_RESEARCH")
            .put("source_span", "Search Android security update")
            .put("query_span", "Android security update")
            .put("confidence", .96)
        assertNotNull(ModelResearchGoalProposal.fromTool(11, valid))
        assertNull(ModelResearchGoalProposal.fromTool(0, valid))
        assertNull(ModelResearchGoalProposal.fromTool(11, valid.put("execute", true)))
        valid.remove("execute")
        assertNull(ModelResearchGoalProposal.fromTool(11, valid.put("confidence", .2)))
    }
}
