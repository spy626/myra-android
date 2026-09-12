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

    /** A recognized read-only question remains valid while delayed final ASR belongs
     * to the same speech identity. Time alone must not force the slow final path. */
    fun mayDispatchForIdentity(armedTurnId: Long, activeVoiceTurnId: Long?): Boolean =
        armedTurnId != 0L && activeVoiceTurnId == armedTurnId
}

enum class ScreenQuestionReconciliation { MATCH, MATERIAL_CHANGE }

object EarlyScreenQuestionPolicy {
    const val STABILIZATION_MS = 180L

    fun mayAuthorizeAtSpeechEnd(text: String, speechActive: Boolean): Boolean =
        !speechActive && FastVisualRequestClassifier.classify(text)?.kind == FastVisualKind.QUESTION

    fun reconcile(partial: String, finalText: String): ScreenQuestionReconciliation {
        val partialKind = FastVisualRequestClassifier.classify(partial)?.kind
        val finalKind = FastVisualRequestClassifier.classify(finalText)?.kind
        return if (partialKind == FastVisualKind.QUESTION && finalKind == FastVisualKind.QUESTION) {
            ScreenQuestionReconciliation.MATCH
        } else ScreenQuestionReconciliation.MATERIAL_CHANGE
    }
}
