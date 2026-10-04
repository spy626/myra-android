package com.myra.assistant.ui.workspace

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WorkspaceAgentReachWebNavigationTest {
    private fun target(url: String = "https://example.com/start") =
        WorkspaceAgentReachPolicy.parse(url)

    private fun response(request: Request, body: String) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("test").header("Content-Type", "text/html; charset=utf-8")
            .body(body.toResponseBody()).build()

    private val html = """
        <html><head><title>Guide</title></head><body>
        <h1>Welcome</h1><p>Find the documentation.</p>
        <a href="/security-updates">Security updates</a>
        <a href="/account/delete">Security account delete</a>
        <a href="https://foreign.example/security">External security</a>
        <script><a href="/malicious-security">Security secret</a></script>
        <form><a href="/security-submit">Security submit</a></form>
        <a href="/features">Product features</a>
        </body></html>
    """.trimIndent()

    private fun firstPage(): WorkspaceAgentReachPublicWeb.Page {
        val root = target()
        return WorkspaceAgentReachPublicWeb.read(
            response(WorkspaceAgentReachPublicWeb.request(root), html), root, root, 100)
    }

    @Test fun staticObservationRemovesScriptFormAndOffsiteAnchorsBeforeChoosing() {
        val page = firstPage()
        assertTrue(page.observedLinks.any { it.url == "https://example.com/security-updates" &&
            it.label == "Security updates" })
        assertFalse(page.observedLinks.any { it.url.contains("malicious-security") ||
            it.url.contains("security-submit") || it.url.contains("foreign.example") })
        val choice = WorkspaceAgentReachWebNavigation.choose(
            page, "Analyze https://example.com/start about security updates")
        assertEquals("https://example.com/security-updates", choice?.target?.canonicalUrl)
        assertEquals(listOf("security", "updates"), choice?.matchedTerms)
    }

    @Test fun bareUrlNoTopicNegationAndSensitiveActionPathsDoNotCauseBlindCrawl() {
        val page = firstPage()
        assertNull(WorkspaceAgentReachWebNavigation.choose(page, "https://example.com/start"))
        assertNull(WorkspaceAgentReachWebNavigation.choose(
            page, "Check https://example.com/start security updates but don't follow links"))
        assertNull(WorkspaceAgentReachWebNavigation.choose(
            page, "Read only this page https://example.com/start security updates"))
        assertNull(WorkspaceAgentReachWebNavigation.choose(
            page, "Read https://example.com/start and explain the whole website"))
        assertNull(WorkspaceAgentReachWebNavigation.choose(
            page, "Read https://example.com/start account delete"))
        assertNull(WorkspaceAgentReachWebNavigation.choose(
            page, "Read https://example.com/start external"))
    }

    @Test fun provenanceMustCorrespondToActualObservedChosenSameHostLink() {
        val root = firstPage()
        val choice = requireNotNull(WorkspaceAgentReachWebNavigation.choose(
            root, "Research https://example.com/start security"))
        val secondary = WorkspaceAgentReachPublicWeb.read(
            response(WorkspaceAgentReachPublicWeb.request(choice.target),
                "<html><head><title>Security bulletin</title></head><body><p>Verified patch notes</p></body></html>"),
            choice.target, choice.target, 200)
        val journey = WorkspaceAgentReachWebNavigation.verify(root, choice, secondary)
        assertEquals(secondary, journey.followed)
        assertTrue(WorkspaceAgentReachReceipt.publicJourney(journey).contains(
            "Follow-up source SHA-256:"))
        val forgedChoice = choice.copy(link = choice.link.copy(url = "https://example.com/forged"))
        assertTrue(runCatching {
            WorkspaceAgentReachWebNavigation.verify(root, forgedChoice, secondary)
        }.isFailure)
    }

    @Test fun runnerObservesThenFollowsExactlyOneRelevantLinkAndVerifies() {
        val root = target()
        val queued = mutableListOf<Pair<Request, (Result<Response>) -> Unit>>()
        val events = mutableListOf<String>()
        val completions = mutableListOf<WorkspaceAgentReachWebNavigation.Journey>()
        val executor = WorkspaceAgentReachPublicWebRunner.Executor { request, callback ->
            queued += request to callback
            object : WorkspaceAgentReachPublicWebRunner.Cancelable {
                override fun cancel() {}
            }
        }
        val runner = WorkspaceAgentReachPublicWebRunner(
            currentTarget = { root },
            listener = object : WorkspaceAgentReachPublicWebRunner.Listener {
                override fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String?) {
                    events += label
                }
                override fun onComplete(journey: WorkspaceAgentReachWebNavigation.Journey) {
                    completions += journey
                }
                override fun onError(message: String) { fail(message) }
            },
            now = { 1234L },
            executor = executor,
        )
        runner.start(root, "Check https://example.com/start security updates")
        assertEquals(1, queued.size)
        assertEquals(root.canonicalUrl, queued[0].first.url.toString())
        queued[0].second(Result.success(response(queued[0].first, html)))
        assertEquals(2, queued.size)
        assertEquals("https://example.com/security-updates", queued[1].first.url.toString())
        assertNull(queued[1].first.header("Authorization"))
        queued[1].second(Result.success(response(queued[1].first,
            "<html><head><title>Updates</title></head><body><h1>Security</h1><p>Fixes listed here</p></body></html>")))
        assertEquals(1, completions.size)
        assertEquals("Updates", completions.single().followed?.title)
        assertEquals(2, queued.size)
        assertTrue(events.any { it.contains("Verified public evidence") })
    }

    @Test fun unavailableFollowUpRetainsOnlyTheInitialVerifiedPageAndCancelDropsStale() {
        val root = target()
        val queued = mutableListOf<Pair<Request, (Result<Response>) -> Unit>>()
        val completions = mutableListOf<WorkspaceAgentReachWebNavigation.Journey>()
        val executor = WorkspaceAgentReachPublicWebRunner.Executor { request, callback ->
            queued += request to callback
            object : WorkspaceAgentReachPublicWebRunner.Cancelable {
                override fun cancel() {}
            }
        }
        val listener = object : WorkspaceAgentReachPublicWebRunner.Listener {
            override fun onEvent(phase: WorkspaceWorkPhase, label: String, detail: String?) {}
            override fun onComplete(journey: WorkspaceAgentReachWebNavigation.Journey) {
                completions += journey
            }
            override fun onError(message: String) { fail(message) }
        }
        val runner = WorkspaceAgentReachPublicWebRunner(
            currentTarget = { root }, listener = listener, now = { 1 }, executor = executor)
        runner.start(root, "Read https://example.com/start security")
        queued[0].second(Result.success(response(queued[0].first, html)))
        queued[1].second(Result.failure(IOException("offline")))
        assertEquals(1, completions.size)
        assertNull(completions.single().followed)
        assertTrue(completions.single().primary.title == "Guide")
        assertTrue(completions.single().followUpStatus.contains("unavailable"))
        runner.start(root, "Read https://example.com/start security")
        val stale = queued.last()
        runner.cancel()
        stale.second(Result.success(response(stale.first, html)))
        assertEquals(1, completions.size)
    }
}
