package com.myra.assistant.service

/** Immutable identity shared by local speech timing, streamed/final ASR, visual work and reply ownership. */
internal data class VoiceTurnIdentity(
    val userTurnId: Long,
    val speechStartAt: Long,
    val speechEndAt: Long = 0L,
    val transcriptTurnId: Long = userTurnId,
    val finalTranscriptId: String? = null
) {
    val consistent: Boolean
        get() = userTurnId != 0L && transcriptTurnId == userTurnId

    fun speechEnded(at: Long): VoiceTurnIdentity = copy(speechEndAt = at)
    fun finalTranscript(id: String): VoiceTurnIdentity = copy(finalTranscriptId = id)
}

internal class VoiceTurnIdentityStore {
    @Volatile private var active: VoiceTurnIdentity? = null

    @Synchronized fun begin(turnId: Long, speechStartAt: Long): VoiceTurnIdentity {
        require(turnId > 0L) { "A genuine speech turn must have a non-zero identity" }
        return VoiceTurnIdentity(turnId, speechStartAt).also { active = it }
    }

    @Synchronized fun speechEnded(turnId: Long, at: Long): VoiceTurnIdentity? =
        active?.takeIf { it.userTurnId == turnId }?.speechEnded(at)?.also { active = it }

    @Synchronized fun finalTranscript(turnId: Long, transcriptId: String): VoiceTurnIdentity? =
        active?.takeIf { it.userTurnId == turnId }?.finalTranscript(transcriptId)?.also { active = it }

    fun current(): VoiceTurnIdentity? = active

    @Synchronized fun clearUnless(turnId: Long) {
        if (active?.userTurnId != turnId) active = null
    }
}

/** A pre-final transcript may identify a likely scroll, but cannot authorize execution. */
internal data class PendingScrollCandidate(
    val turnId: Long,
    val direction: String,
    val detectedAt: Long,
    val source: String = "unknown",
    val foregroundPackage: String? = null,
    val windowId: Int? = null,
    val observedGeneration: Long = 0L
)

internal class PendingScrollCandidateStore {
    @Volatile private var pending: PendingScrollCandidate? = null

    @Synchronized fun stage(
        turnId: Long,
        direction: String,
        detectedAt: Long,
        source: String = "unknown",
        foregroundPackage: String? = null,
        windowId: Int? = null,
        observedGeneration: Long = 0L
    ): PendingScrollCandidate {
        require(turnId > 0L) { "A pending action candidate requires a real user turn" }
        return PendingScrollCandidate(
            turnId, direction, detectedAt, source, foregroundPackage, windowId, observedGeneration
        ).also { pending = it }
    }

    /** Consumes only an exact authoritative-turn match; candidates never rebind across turns. */
    @Synchronized fun consume(authoritativeTurnId: Long): PendingScrollCandidate? {
        val candidate = pending ?: return null
        if (candidate.turnId != authoritativeTurnId) return null
        pending = null
        return candidate
    }

    fun current(): PendingScrollCandidate? = pending

    @Synchronized fun discardForTurn(turnId: Long) {
        if (pending?.turnId == turnId) pending = null
    }
}

internal enum class ScrollProposalAuthorization { PRE_FINAL, FINAL_AUTHORIZED }

internal object ScrollCandidatePolicy {
    const val MAX_AGE_MS = 15_000L

    fun compatible(
        candidate: PendingScrollCandidate,
        authoritativeTurnId: Long,
        foregroundPackage: String?,
        windowId: Int?,
        now: Long
    ): Boolean = candidate.turnId == authoritativeTurnId &&
        now - candidate.detectedAt in 0..MAX_AGE_MS &&
        (candidate.foregroundPackage == null || candidate.foregroundPackage == foregroundPackage) &&
        (candidate.windowId == null || candidate.windowId == windowId)
}

internal object RuntimeActionBindingGuard {
    fun matches(requestedTurnId: Long, requestedTaskId: String, taskTurnId: Long, activeTaskId: String): Boolean =
        requestedTurnId > 0L && requestedTurnId == taskTurnId &&
            requestedTaskId.isNotBlank() && requestedTaskId == activeTaskId
}

/** A later VAD cycle is a new user utterance even if Gemini has not delivered the
 * previous final transcript. This changes identity only; it never authorizes a tool. */
internal object SpeechCycleBoundaryPolicy {
    const val MIN_INDEPENDENT_GAP_MS = 700L

    fun startsNewTurn(
        activeTurnId: Long,
        previousSpeechEndedAt: Long,
        newSpeechStartedAt: Long,
        transcriptStarted: Boolean
    ): Boolean = activeTurnId > 0L && previousSpeechEndedAt > 0L && !transcriptStarted &&
        newSpeechStartedAt - previousSpeechEndedAt >= MIN_INDEPENDENT_GAP_MS
}
