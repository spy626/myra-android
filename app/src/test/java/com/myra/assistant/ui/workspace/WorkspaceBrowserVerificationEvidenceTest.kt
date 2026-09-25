package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceBrowserVerificationEvidenceTest {
    private val e = WorkspaceBrowserVerificationEvidence

    @Test fun clearBoundedEvidenceNeverClaimsPhoneOrVisualPass() {
        val snapshot = e.Snapshot(
            pageUrl = "http://127.0.0.1:1234/p/x/index.html",
            capturedAtMs = 1L,
            dom = e.DomSignals(
                overflow = false,
                brokenImages = 0,
                emptyMedia = 0,
                interactiveControls = 3,
                unlabeledControls = 0,
            ),
            runtime = e.RuntimeSignals(),
        )
        val result = e.assess(snapshot)
        assertEquals(e.Assessment.BOUNDED_CLEAR, result.assessment)
        assertTrue(e.Plane.DOM in result.planes)
        assertTrue(e.Plane.ACCESSIBILITY in result.planes)
        assertTrue(e.Plane.CONSOLE in result.planes)
        assertTrue(e.Plane.NETWORK in result.planes)
        assertTrue(result.statusText().contains("phone/visual verification"))
        assertFalse(result.statusText().contains("PASS"))
    }

    @Test fun deterministicRuntimeAndDomIssuesAreReported() {
        val result = e.assess(e.Snapshot(
            pageUrl = "http://127.0.0.1/preview",
            capturedAtMs = 2L,
            dom = e.DomSignals(true, 2, 1, 4, 1),
            runtime = e.RuntimeSignals(
                consoleErrors = 2,
                consoleWarnings = 3,
                networkFailures = 1,
                httpErrors = 1,
            ),
        ))
        assertEquals(e.Assessment.ISSUES_FOUND, result.assessment)
        assertTrue(result.findings.any { it.contains("horizontal overflow") })
        assertTrue(result.findings.any { it.contains("broken image") })
        assertTrue(result.findings.any { it.contains("unlabeled") })
        assertTrue(result.findings.any { it.contains("console error") })
        assertTrue(result.findings.any { it.contains("network load") })
        assertTrue(result.findings.any { it.contains("HTTP error") })
    }

    @Test fun missingDomObservationIsUnknownNotSuccess() {
        val result = e.assess(e.Snapshot(
            pageUrl = "http://127.0.0.1/preview",
            capturedAtMs = 3L,
            dom = null,
            runtime = e.RuntimeSignals(),
        ))
        assertEquals(e.Assessment.UNKNOWN, result.assessment)
        assertTrue(result.statusText().contains("do not treat this as PASS"))
    }

    @Test fun decodesOnlyBoundedCountOnlyDomShape() {
        val encoded = JSONObject.quote(
            """{"overflow":false,"brokenImages":1,"emptyMedia":0,"interactiveControls":5,"unlabeledControls":2}""")
        val dom = requireNotNull(e.decodeDomResult(encoded))
        assertEquals(1, dom.brokenImages)
        assertEquals(5, dom.interactiveControls)
        assertEquals(2, dom.unlabeledControls)

        assertNull(e.decodeDomResult("{}"))
        assertNull(e.decodeDomResult(JSONObject.quote(
            """{"overflow":false,"brokenImages":0,"emptyMedia":0,"interactiveControls":2,"unlabeledControls":3}""")))
        assertNull(e.decodeDomResult(JSONObject.quote(
            """{"overflow":false,"brokenImages":0,"emptyMedia":0,"interactiveControls":301,"unlabeledControls":0}""")))
    }

    @Test fun scriptDoesNotReadStorageCookiesOrFormValues() {
        val script = e.DOM_AUDIT_SCRIPT.lowercase()
        assertFalse(script.contains("localstorage"))
        assertFalse(script.contains("sessionstorage"))
        assertFalse(script.contains("document.cookie"))
        assertFalse(script.contains(".value"))
        assertTrue(script.contains("json.stringify"))
    }
}
