package com.myra.assistant.service

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
        return UserMessageCommitResult.Accepted(messageId, message)
    }
}
