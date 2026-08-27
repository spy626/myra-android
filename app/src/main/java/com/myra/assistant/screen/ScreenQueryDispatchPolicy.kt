package com.myra.assistant.screen

/** Intent being armed is not proof that its fresh-frame query was actually dispatched. */
object ScreenQueryDispatchPolicy {
    fun shouldDispatch(screenResponseActive: Boolean, dispatchedTurnId: Long, currentTurnId: Long): Boolean =
        !screenResponseActive && currentTurnId != 0L && dispatchedTurnId != currentTurnId
}
