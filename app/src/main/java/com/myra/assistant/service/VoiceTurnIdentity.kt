package com.myra.assistant.service

/** Immutable identity shared by local speech timing, streamed/final ASR, visual work and reply ownership. */
internal data class VoiceTurnIdentity(
    val userTurnId: Long,
    val speechStartAt: Long,
    val speechEndAt: Long = 0L,
    val transcriptTurnId: Long = userTurnId,
    val finalTranscriptId: String? = null,
    val lifecycle: UserUtteranceState = UserUtteranceState.SPEECH_ACTIVE
) {
    val consistent: Boolean
        get() = userTurnId != 0L && transcriptTurnId == userTurnId

    fun speechEnded(at: Long): VoiceTurnIdentity = copy(
        speechEndAt = at,
        lifecycle = UserUtteranceState.SPEECH_ENDED_AWAITING_TRANSCRIPT
    )
    fun finalTranscript(id: String): VoiceTurnIdentity = copy(
        finalTranscriptId = id,
        lifecycle = UserUtteranceState.FINAL_TRANSCRIPT_COMMITTED
    )
    fun terminal(): VoiceTurnIdentity = copy(lifecycle = UserUtteranceState.TERMINAL)
}

internal enum class UserUtteranceState {
    IDLE,
    SPEECH_ACTIVE,
    SPEECH_ENDED_AWAITING_TRANSCRIPT,
    FINAL_TRANSCRIPT_COMMITTED,
    RESPONSE_ACTIVE,
    TERMINAL;

    val closedForTranscript: Boolean
        get() = this == FINAL_TRANSCRIPT_COMMITTED || this == TERMINAL
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

    @Synchronized fun terminal(turnId: Long): VoiceTurnIdentity? =
        active?.takeIf { it.userTurnId == turnId }?.terminal()?.also { active = it }

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
        transcriptStarted: Boolean,
        utteranceState: UserUtteranceState? = null
    ): Boolean {
        if (activeTurnId <= 0L) return true
        if (utteranceState?.closedForTranscript == true) return true
        if (previousSpeechEndedAt <= 0L) return false
        // A short VAD split may still be one physical sentence. Once the gap is an
        // independent speech episode, partial text must not pin the old identity.
        return newSpeechStartedAt - previousSpeechEndedAt >= MIN_INDEPENDENT_GAP_MS
    }
}

/** Binds streamed transcript text to one user utterance and freezes it at final commit. */
internal class TranscriptAccumulatorOwnership {
    private var utteranceId: String? = null
    private var terminal = false

    @Synchronized fun bind(id: String) {
        utteranceId = id
        terminal = false
    }

    @Synchronized fun close(id: String): Boolean {
        if (utteranceId != id) return false
        terminal = true
        return true
    }

    @Synchronized fun mayAppend(id: String): Boolean = utteranceId == id && !terminal
    @Synchronized fun currentId(): String? = utteranceId
    @Synchronized fun isTerminal(): Boolean = terminal
}
