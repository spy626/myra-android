package com.myra.assistant.diagnostics

/** Observation only: never used to authorize a gesture or supply routing context. */
class ScrollContinuationTelemetry(private val log: (String) -> Unit) {
    private data class Evidence(val turn: Long, val task: String, val direction: String?, val accepted: Boolean,
        val at: Long, val pkg: String?, val window: Int?, val generation: Long, val verification: String)
    private var evidence: Evidence? = null
    @Synchronized fun dispatched(turn: Long, task: String, direction: String?, accepted: Boolean,
        now: Long, pkg: String?, window: Int?, generation: Long) {
        evidence?.let { log("SCROLL_CONTINUATION_CONTEXT_CLEARED sourceTurnId=${it.turn} taskId=${it.task} reason=new_scroll_attempt diagnosticOnly=true") }
        evidence = Evidence(turn, task, direction, accepted, now, pkg, window, generation, "PENDING")
        log("SCROLL_CONTINUATION_CONTEXT_CREATED sourceTurnId=$turn taskId=$task direction=$direction physicalGestureAccepted=$accepted verificationState=PENDING createdAt=$now expiresAt=NA foregroundPackage=$pkg windowId=$window generation=$generation diagnosticOnly=true")
    }
    @Synchronized fun verified(task: String, state: String) {
        val old = evidence?.takeIf { it.task == task } ?: return
        evidence = old.copy(verification = state)
        log("SCROLL_CONTINUATION_CONTEXT_UPDATED sourceTurnId=${old.turn} taskId=$task direction=${old.direction} physicalGestureAccepted=${old.accepted} verificationState=$state createdAt=${old.at} expiresAt=NA foregroundPackage=${old.pkg} windowId=${old.window} generation=${old.generation} diagnosticOnly=true")
    }
    @Synchronized fun resolution(turn: Long, text: String, now: Long, pkg: String?, window: Int?,
        contextPresent: Boolean, decision: String) {
        val old = evidence
        log("SCROLL_FOLLOWUP_RESOLUTION turnId=$turn semanticText=${text.take(120)} continuationContextPresent=$contextPresent " +
            "contextAgeMs=${old?.let { (now - it.at).coerceAtLeast(0) } ?: "NA"} lastDirection=${old?.direction} " +
            "lastPhysicalGestureAccepted=${old?.accepted} lastVerificationState=${old?.verification} " +
            "foregroundCompatible=${old?.pkg == pkg} windowCompatible=${old?.window == window} decision=$decision " +
            "rejectionReason=${if (!contextPresent) "verified_completed_scroll_context_unavailable" else "see_final_intent"}")
    }
}
