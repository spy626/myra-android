package com.myra.assistant.service

import com.myra.assistant.ai.LiveTranscriptAssembler
import com.myra.assistant.data.memory.LocalRecallIntent
import com.myra.assistant.data.memory.VerifiedMemorySpeechEquivalence

internal data class EarlyMemoryBufferedAudio(
    val generationId: Long,
    val chunks: List<ByteArray>,
    val modelTranscript: String
)

/**
 * Read-only speculative recall gate. It buffers ordinary Gemini audio until the existing
 * Room-backed MemoryBrainCoordinator has verified the answer. It never writes memory.
 */
internal class EarlyMemoryRecallGate {
    private val lock = Any()
    private var turnId = 0L
    private var intent: LocalRecallIntent? = null
    private var validPreview = false
    private var generationId = 0L
    private val audio = mutableListOf<ByteArray>()
    private val modelTranscript = StringBuilder()
    private var verifiedResponse: String? = null
    private var generationComplete = false
    private var released = false
    private var toolGrounded = false
    private var toolGroundedAfterGenerationId = 0L

    fun arm(turnId: Long, intent: LocalRecallIntent): Boolean = synchronized(lock) {
        if (turnId <= 0L || this.turnId == turnId) return@synchronized false
        clearLocked()
        this.turnId = turnId
        this.intent = intent
        validPreview = true
        true
    }

    fun currentIntent(turnId: Long): LocalRecallIntent? = synchronized(lock) {
        intent?.takeIf { this.turnId == turnId }
    }

    fun isCapturing(turnId: Long): Boolean = synchronized(lock) {
        this.turnId == turnId && intent != null && !released
    }

    fun invalidatePreview(turnId: Long) = synchronized(lock) {
        if (this.turnId == turnId) validPreview = false
    }

    fun captureAudio(turnId: Long, generationId: Long, pcm: ByteArray): Boolean = synchronized(lock) {
        if (this.turnId != turnId || intent == null || released || toolGrounded) return@synchronized false
        if (this.generationId == 0L) this.generationId = generationId
        if (this.generationId == generationId && pcm.isNotEmpty()) audio += pcm.copyOf()
        true
    }

    fun appendModelTranscript(turnId: Long, text: String): Boolean = synchronized(lock) {
        if (this.turnId != turnId || intent == null || released && !toolGrounded) return@synchronized false
        LiveTranscriptAssembler.append(modelTranscript, text)
        true
    }

    fun authorizeToolGrounded(turnId: Long, afterGenerationId: Long): Boolean = synchronized(lock) {
        if (this.turnId != turnId || intent == null || !validPreview) return@synchronized false
        toolGrounded = true
        toolGroundedAfterGenerationId = afterGenerationId
        generationId = 0L
        released = false
        audio.clear()
        modelTranscript.clear()
        true
    }

    fun acceptToolGroundedAudio(turnId: Long, incomingGenerationId: Long): Boolean = synchronized(lock) {
        if (this.turnId != turnId || intent == null || !validPreview || !toolGrounded) return@synchronized false
        if (incomingGenerationId <= toolGroundedAfterGenerationId) return@synchronized false
        if (generationId == 0L) generationId = incomingGenerationId
        if (generationId != incomingGenerationId) return@synchronized false
        released = true
        true
    }

    fun markVerified(turnId: Long, response: String): Boolean = synchronized(lock) {
        if (this.turnId != turnId || intent == null || released || response.isBlank()) return@synchronized false
        verifiedResponse = response
        true
    }

    fun markGenerationComplete(turnId: Long, generationId: Long): Boolean = synchronized(lock) {
        if (this.turnId != turnId || intent == null || released) return@synchronized false
        if (this.generationId != 0L && this.generationId != generationId) return@synchronized false
        this.generationId = generationId
        generationComplete = true
        true
    }

    fun takeVerifiedRelease(turnId: Long): EarlyMemoryBufferedAudio? = synchronized(lock) {
        if (this.turnId != turnId || intent == null || !validPreview || released) return@synchronized null
        val verified = verifiedResponse ?: return@synchronized null
        if (audio.isEmpty() || modelTranscript.isBlank() || generationId == 0L) return@synchronized null
        if (!VerifiedMemorySpeechEquivalence.matches(modelTranscript.toString(), verified)) return@synchronized null
        released = true
        val result = EarlyMemoryBufferedAudio(generationId, audio.map(ByteArray::copyOf), modelTranscript.toString())
        audio.clear()
        result
    }

    fun acceptsReleasedAudio(turnId: Long, generationId: Long): Boolean = synchronized(lock) {
        this.turnId == turnId && released && this.generationId == generationId && generationId != 0L
    }

    fun verifiedResponse(turnId: Long): String? = synchronized(lock) {
        verifiedResponse?.takeIf { this.turnId == turnId }
    }

    fun wasReleased(turnId: Long): Boolean = synchronized(lock) {
        this.turnId == turnId && released
    }

    fun takeBufferedForOrdinary(turnId: Long): EarlyMemoryBufferedAudio? = synchronized(lock) {
        if (this.turnId != turnId || intent == null || released) return@synchronized null
        val result = EarlyMemoryBufferedAudio(generationId, audio.map(ByteArray::copyOf), modelTranscript.toString())
        clearLocked()
        result
    }

    fun clear(turnId: Long) = synchronized(lock) {
        if (this.turnId == turnId) clearLocked()
    }

    private fun clearLocked() {
        turnId = 0L
        intent = null
        validPreview = false
        generationId = 0L
        audio.clear()
        modelTranscript.clear()
        verifiedResponse = null
        generationComplete = false
        released = false
        toolGrounded = false
        toolGroundedAfterGenerationId = 0L
    }
}
