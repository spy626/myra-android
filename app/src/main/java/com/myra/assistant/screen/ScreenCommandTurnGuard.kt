package com.myra.assistant.screen

/** Prevents late ASR/tool candidates from committing a second screen command for one voice turn. */
class ScreenCommandTurnGuard {
    private val committed = LinkedHashSet<Long>()

    @Synchronized fun tryCommit(turnId: Long): Boolean {
        if (turnId <= 0L || turnId in committed) return false
        committed += turnId
        while (committed.size > 64) committed.remove(committed.first())
        return true
    }

    @Synchronized fun hasCommitted(turnId: Long): Boolean = turnId in committed
    @Synchronized fun clear() = committed.clear()
}
