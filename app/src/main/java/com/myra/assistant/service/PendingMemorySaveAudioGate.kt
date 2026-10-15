package com.myra.assistant.service

import com.myra.assistant.ai.LiveTranscriptAssembler

internal data class BufferedMemorySaveAudio(
    val generationId: Long,
    val chunks: List<ByteArray>,
    val transcript: String
)

internal class PendingMemorySaveAudioGate(
    private val maxBytes: Int = 512 * 1024
) {
    private val lock = Any()
    private var turnId = 0L
    private var afterGenerationId = 0L
    private var generationId = 0L
    private var bytes = 0
    private var overflowed = false
    private val audio = mutableListOf<ByteArray>()
    private val transcript = StringBuilder()

    fun arm(turnId: Long, afterGenerationId: Long): Boolean = synchronized(lock) {
        if (turnId <= 0L) return@synchronized false
        if (this.turnId == turnId) return@synchronized true
        clearLocked()
        this.turnId = turnId
        this.afterGenerationId = afterGenerationId
        true
    }

    fun capture(turnId: Long, incomingGenerationId: Long, pcm: ByteArray): Boolean = synchronized(lock) {
        if (this.turnId != turnId || incomingGenerationId <= afterGenerationId || pcm.isEmpty()) {
            return@synchronized false
        }
        if (generationId == 0L) generationId = incomingGenerationId
        if (generationId != incomingGenerationId) return@synchronized false
        if (overflowed) return@synchronized true
        if (bytes + pcm.size > maxBytes) {
            overflowed = true
            audio.clear()
            bytes = 0
            return@synchronized true
        }
        audio += pcm.copyOf()
        bytes += pcm.size
        true
    }

    fun appendTranscript(turnId: Long, incomingGenerationId: Long, text: String): Boolean = synchronized(lock) {
        if (this.turnId != turnId || incomingGenerationId <= afterGenerationId || text.isBlank()) {
            return@synchronized false
        }
        if (generationId == 0L) generationId = incomingGenerationId
        if (generationId != incomingGenerationId) return@synchronized false
        LiveTranscriptAssembler.append(transcript, text)
        true
    }

    fun take(turnId: Long): BufferedMemorySaveAudio? = synchronized(lock) {
        if (this.turnId != turnId || generationId == 0L || audio.isEmpty() || overflowed) {
            clearLocked()
            return@synchronized null
        }
        val result = BufferedMemorySaveAudio(
            generationId,
            audio.map(ByteArray::copyOf),
            transcript.toString()
        )
        clearLocked()
        result
    }

    fun clear(turnId: Long) = synchronized(lock) {
        if (this.turnId == turnId) clearLocked()
    }

    private fun clearLocked() {
        turnId = 0L
        afterGenerationId = 0L
        generationId = 0L
        bytes = 0
        overflowed = false
        audio.clear()
        transcript.clear()
    }
}
