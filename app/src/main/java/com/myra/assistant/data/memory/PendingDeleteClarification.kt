package com.myra.assistant.data.memory

/** Resolves the short name spoken after LYRA explicitly asks which memory to delete. */
object PendingDeleteClarification {
    private val personName = Regex("^[\\p{L}][\\p{L}'-]{1,30}$")
    private val nonNames = setOf("yes", "haan", "han", "no", "nahi", "okay", "ok")

    fun resolve(raw: String): MemoryCommand.Forget? {
        val clean = raw.trim().trimEnd('.', ',', '?', '!', '।')
        if (!personName.matches(clean) || clean.lowercase() in nonNames) return null
        return MemoryCommand.Forget(BestFriendNameCanonicalizer.canonicalize(clean))
    }
}
