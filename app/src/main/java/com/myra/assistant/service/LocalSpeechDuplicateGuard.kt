package com.myra.assistant.service

/** Drops a duplicate callback only while that same natural utterance is still active. */
object LocalSpeechDuplicateGuard {
    fun shouldDrop(sameMessage: Boolean, speechBusy: Boolean): Boolean = sameMessage && speechBusy
}
