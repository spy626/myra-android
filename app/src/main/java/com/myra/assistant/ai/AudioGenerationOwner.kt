package com.myra.assistant.ai

internal class AudioGenerationOwner {
    data class Authorization(val previousGeneration: Long, val concurrent: Boolean)
    @Volatile private var generation = 0L
    @Volatile private var owner = "NONE"

    @Synchronized fun authorize(generationId: Long, responseOwner: String): Authorization {
        val previous = generation
        val concurrent = previous != 0L && (previous != generationId || owner != responseOwner)
        generation = generationId
        owner = responseOwner
        return Authorization(previous, concurrent)
    }

    fun accepts(generationId: Long, responseOwner: String): Boolean =
        generationId != 0L && generationId == generation && responseOwner == owner
    fun generationId(): Long = generation
    fun owner(): String = owner

    @Synchronized fun clear() { generation = 0L; owner = "NONE" }
}
