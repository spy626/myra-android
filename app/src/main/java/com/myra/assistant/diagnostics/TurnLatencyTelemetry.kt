package com.myra.assistant.diagnostics

data class TurnLatencySnapshot(
    val turnId: Long,
    var speechActivityStartedAt: Long? = null,
    var speechActivityEndedAt: Long? = null,
    var authoritativeUserTurnCompleteAt: Long? = null,
    var clientInputTurnStartedAt: Long? = null,
    var clientInputTurnCompletedAt: Long? = null,
    var firstServerResponseEventAt: Long? = null,
    var firstToolProposalAt: Long? = null,
    var firstModelAudioPacketAt: Long? = null,
    var firstAcceptedModelAudioAt: Long? = null,
    var modelGenerationCompletedAt: Long? = null,
    var finalTranscriptReceivedAt: Long? = null,
    var finalTranscriptAcceptedAt: Long? = null,
    var intentResolvedAt: Long? = null,
    var taskCreatedAt: Long? = null,
    var runtimeEnteredAt: Long? = null,
    var actionStartedAt: Long? = null,
    var actionReturnedAt: Long? = null,
    var postActionObservationScheduledAt: Long? = null,
    var postActionObservationStartedAt: Long? = null,
    var postActionObservationReadyAt: Long? = null,
    var verificationStartedAt: Long? = null,
    var verificationCompletedAt: Long? = null
)

/** Passive, per-turn timing only. It never authorizes or delays a turn. */
class TurnLatencyTelemetry(private val log: (String) -> Unit) {
    private val turns = LinkedHashMap<Long, TurnLatencySnapshot>()
    private val acceptedAudioGenerations = mutableSetOf<Pair<Long, Long>>()

    @Synchronized fun begin(turnId: Long, at: Long): TurnLatencySnapshot =
        turns.getOrPut(turnId) { TurnLatencySnapshot(turnId) }.also { it.speechActivityStartedAt = at }

    @Synchronized fun record(turnId: Long, field: Field, at: Long, generationId: Long = 0L) {
        if (turnId <= 0L || at <= 0L) return
        val value = turns.getOrPut(turnId) { TurnLatencySnapshot(turnId) }
        when (field) {
            Field.SPEECH_END -> value.speechActivityEndedAt = at
            Field.AUTHORITATIVE_COMPLETE -> value.authoritativeUserTurnCompleteAt = at
            Field.INPUT_STARTED -> value.clientInputTurnStartedAt = value.clientInputTurnStartedAt ?: at
            Field.INPUT_COMPLETED -> value.clientInputTurnCompletedAt = at
            Field.FIRST_SERVER_EVENT -> value.firstServerResponseEventAt = value.firstServerResponseEventAt ?: at
            Field.FIRST_TOOL_PROPOSAL -> value.firstToolProposalAt = value.firstToolProposalAt ?: at
            Field.FIRST_MODEL_AUDIO_PACKET -> value.firstModelAudioPacketAt = value.firstModelAudioPacketAt ?: at
            Field.FIRST_ACCEPTED_MODEL_AUDIO -> {
                if (acceptedAudioGenerations.add(turnId to generationId)) {
                    value.firstAcceptedModelAudioAt = value.firstAcceptedModelAudioAt ?: at
                    log("FIRST_ACCEPTED_MODEL_AUDIO_AT turnId=$turnId generationId=$generationId timestamp=$at")
                }
            }
            Field.MODEL_GENERATION_COMPLETED -> value.modelGenerationCompletedAt = at
            Field.FINAL_TRANSCRIPT_RECEIVED -> value.finalTranscriptReceivedAt = value.finalTranscriptReceivedAt ?: at
            Field.FINAL_TRANSCRIPT_ACCEPTED -> value.finalTranscriptAcceptedAt = at
            Field.INTENT_RESOLVED -> value.intentResolvedAt = at
            Field.TASK_CREATED -> value.taskCreatedAt = at
            Field.RUNTIME_ENTERED -> value.runtimeEnteredAt = at
            Field.ACTION_STARTED -> value.actionStartedAt = at
            Field.ACTION_RETURNED -> value.actionReturnedAt = at
            Field.OBSERVATION_SCHEDULED -> value.postActionObservationScheduledAt = at
            Field.OBSERVATION_STARTED -> value.postActionObservationStartedAt = at
            Field.OBSERVATION_READY -> value.postActionObservationReadyAt = at
            Field.VERIFICATION_STARTED -> value.verificationStartedAt = at
            Field.VERIFICATION_COMPLETED -> value.verificationCompletedAt = at
        }
    }

    @Synchronized fun snapshot(turnId: Long): TurnLatencySnapshot? = turns[turnId]?.copy()

    @Synchronized fun logBreakdown(turnId: Long, capability: String) {
        val t = turns[turnId] ?: return
        fun d(start: Long?, end: Long?): String =
            if (start != null && end != null && end >= start) (end - start).toString() else "NA"
        log(
            "ACTION_LATENCY_BREAKDOWN_V2 turnId=$turnId capability=$capability " +
                "speechEndToInputTurnMs=${d(t.speechActivityEndedAt, t.clientInputTurnStartedAt)} " +
                "speechEndToFirstServerEventMs=${d(t.speechActivityEndedAt, t.firstServerResponseEventAt)} " +
                "speechEndToToolProposalMs=${d(t.speechActivityEndedAt, t.firstToolProposalAt)} " +
                "speechEndToFinalTranscriptMs=${d(t.speechActivityEndedAt, t.finalTranscriptReceivedAt)} " +
                "speechEndToFirstAcceptedAudioMs=${d(t.speechActivityEndedAt, t.firstAcceptedModelAudioAt)} " +
                "finalTranscriptToIntentMs=${d(t.finalTranscriptAcceptedAt, t.intentResolvedAt)} " +
                "intentToTaskMs=${d(t.intentResolvedAt, t.taskCreatedAt)} taskToRuntimeMs=${d(t.taskCreatedAt, t.runtimeEnteredAt)} " +
                "runtimeToActionMs=${d(t.runtimeEnteredAt, t.actionStartedAt)} actionDurationMs=${d(t.actionStartedAt, t.actionReturnedAt)} " +
                "actionReturnToObservationScheduledMs=${d(t.actionReturnedAt, t.postActionObservationScheduledAt)} " +
                "observationScheduleDelayMs=${d(t.postActionObservationScheduledAt, t.postActionObservationStartedAt)} " +
                "observationDurationMs=${d(t.postActionObservationStartedAt, t.postActionObservationReadyAt)} " +
                "verificationDurationMs=${d(t.verificationStartedAt, t.verificationCompletedAt)} " +
                "speechEndToVisibleActionEstimateMs=${d(t.speechActivityEndedAt, t.actionReturnedAt)}"
        )
    }

    enum class Field {
        SPEECH_END, AUTHORITATIVE_COMPLETE, INPUT_STARTED, INPUT_COMPLETED,
        FIRST_SERVER_EVENT, FIRST_TOOL_PROPOSAL, FIRST_MODEL_AUDIO_PACKET,
        FIRST_ACCEPTED_MODEL_AUDIO, MODEL_GENERATION_COMPLETED,
        FINAL_TRANSCRIPT_RECEIVED, FINAL_TRANSCRIPT_ACCEPTED, INTENT_RESOLVED,
        TASK_CREATED, RUNTIME_ENTERED, ACTION_STARTED, ACTION_RETURNED,
        OBSERVATION_SCHEDULED, OBSERVATION_STARTED, OBSERVATION_READY,
        VERIFICATION_STARTED, VERIFICATION_COMPLETED
    }
}

