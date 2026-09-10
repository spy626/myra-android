package com.myra.assistant.service

import com.myra.assistant.data.memory.JarvisSimpleMemoryRuntime

internal data class FinalUserMessage(
    val sessionId: String,
    val turnId: Long,
    val utteranceId: String,
    val raw: String,
    val normalized: String,
    val display: String
)

internal sealed interface UserMessageCommitResult {
    data class Accepted(val messageId: String, val message: FinalUserMessage) : UserMessageCommitResult
    data class AlreadyCommitted(val existingMessageId: String) : UserMessageCommitResult
}

/** Identity-based exactly-once gate; repeated text in a different turn is valid. */
internal class FinalUserMessageCommitter {
    private val committed = linkedMapOf<String, String>()

    fun commit(message: FinalUserMessage): UserMessageCommitResult {
        committed[message.utteranceId]?.let { return UserMessageCommitResult.AlreadyCommitted(it) }
        val messageId = "user:${message.utteranceId}"
        committed[message.utteranceId] = messageId
        while (committed.size > 100) committed.remove(committed.keys.first())

        // JARVIS-style truth capture happens for every accepted final user turn, not only
        // turns that Gemini classified as a memory command. The store is idempotent by utterance id.
        JarvisSimpleMemoryRuntime.recordFinalUserMessage(
            sessionId = message.sessionId,
            turnId = message.turnId,
            utteranceId = message.utteranceId,
            text = message.display.ifBlank { message.raw }
        )
        return UserMessageCommitResult.Accepted(messageId, message)
    }
}
