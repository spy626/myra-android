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
