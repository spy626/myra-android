package com.myra.assistant.screen

/** Intent being armed is not proof that its fresh-frame query was actually dispatched. */
object ScreenQueryDispatchPolicy {
    fun shouldDispatch(screenResponseActive: Boolean, dispatchedTurnId: Long, currentTurnId: Long): Boolean =
        !screenResponseActive && currentTurnId != 0L && dispatchedTurnId != currentTurnId
}

object ArmedScreenQuestionPolicy {
    const val MAX_AGE_MS = 2_500L

    fun isFresh(detectedAt: Long, now: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        detectedAt > 0L && now >= detectedAt && now - detectedAt <= maxAgeMs
}
