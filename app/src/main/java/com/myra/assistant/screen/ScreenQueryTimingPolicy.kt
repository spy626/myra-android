package com.myra.assistant.screen

data class ScreenQuerySpeechTiming(val consistent: Boolean, val speechEndAt: Long)

object ScreenQueryTimingPolicy {
    fun bind(userTurnId: Long, speechTimingTurnId: Long, speechEndAt: Long): ScreenQuerySpeechTiming {
        val consistent = userTurnId != 0L && userTurnId == speechTimingTurnId && speechEndAt > 0L
        return ScreenQuerySpeechTiming(consistent, if (consistent) speechEndAt else 0L)
    }
}
