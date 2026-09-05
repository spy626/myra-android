package com.myra.assistant.service

import com.myra.assistant.agent.GroundedActionClaimPolicy
import com.myra.assistant.agent.GroundedActionResultState

internal enum class AssistantResponseOwner {
    MODEL, CONTROLLED_LOCAL, CONTROLLED_SCREEN, TOOL_CALLBACK, LEGACY_COMMAND, RUNTIME
}

internal data class ResponseGenerationIdentity(
    val responseId: String,
    val sourceTurnId: Long,
    val responseGenerationId: Long,
    val owner: AssistantResponseOwner
)

/** Response identity is independent of the user utterance lifetime and message text. */
internal class ResponseGenerationIdentityStore(private val sessionId: String) {
    private var sequence = 0L
    private val byId = linkedMapOf<String, ResponseGenerationIdentity>()

    @Synchronized fun create(sourceTurnId: Long, owner: AssistantResponseOwner): ResponseGenerationIdentity {
        val generation = ++sequence
        val responseId = "assistant:$sessionId:$sourceTurnId:$generation"
        return ResponseGenerationIdentity(responseId, sourceTurnId, generation, owner).also {
            byId[responseId] = it
            while (byId.size > 64) byId.remove(byId.keys.first())
        }
    }

    @Synchronized fun get(responseId: String): ResponseGenerationIdentity? = byId[responseId]
}

internal data class GroundedPhysicalAction(
    val turnId: Long,
    val taskId: String,
    val capability: String,
    val state: GroundedActionResultState
)

internal class GroundedActionLedger {
    private val byTurn = linkedMapOf<Long, GroundedPhysicalAction>()

    @Synchronized fun record(action: GroundedPhysicalAction) {
        if (action.turnId <= 0L || action.taskId.isBlank() || action.capability.isBlank()) return
        byTurn[action.turnId] = action
        while (byTurn.size > 32) byTurn.remove(byTurn.keys.first())
    }

    @Synchronized fun forTurn(turnId: Long): GroundedPhysicalAction? = byTurn[turnId]
}

internal enum class PhysicalActionClaimDecision { ALLOW, BLOCK, REWRITE }

internal data class PhysicalActionClaimVerdict(
    val decision: PhysicalActionClaimDecision,
    val reason: String
)

/** The final boundary shared by model, controlled, tool, legacy and runtime replies. */
internal object PhysicalActionClaimGate {
    fun evaluate(
        text: String,
        turnId: Long,
        grounded: GroundedPhysicalAction?
    ): PhysicalActionClaimVerdict {
        val claimedCapability = GroundedActionClaimPolicy.claimedCapability(text)
            ?: return PhysicalActionClaimVerdict(PhysicalActionClaimDecision.ALLOW, "not_a_physical_success_claim")
        if (grounded == null || grounded.turnId != turnId) {
            return PhysicalActionClaimVerdict(PhysicalActionClaimDecision.BLOCK, "no_same_turn_runtime_action")
        }
        if (grounded.state != GroundedActionResultState.VERIFIED_SUCCESS) {
            return PhysicalActionClaimVerdict(
                PhysicalActionClaimDecision.BLOCK,
                "runtime_state_${grounded.state.name.lowercase()}"
            )
        }
        if (!GroundedActionClaimPolicy.capabilityMatches(claimedCapability, grounded.capability)) {
            return PhysicalActionClaimVerdict(PhysicalActionClaimDecision.BLOCK, "capability_mismatch")
        }
        return PhysicalActionClaimVerdict(PhysicalActionClaimDecision.ALLOW, "same_turn_verified_success")
    }
}

internal enum class ChatRole { USER, ASSISTANT }

internal data class StoredChatMessage(
    val messageId: String,
    val turnId: Long,
    val role: ChatRole,
    val text: String,
    val error: Boolean = false
)

internal sealed interface ChatMessageStoreResult {
    data class Accepted(val message: StoredChatMessage) : ChatMessageStoreResult
    data class AlreadyStored(val messageId: String) : ChatMessageStoreResult
}

/** Exactly-once service-side store immediately before observable UI delivery. */
internal class ChatMessageDeliveryStore {
    private val messages = linkedMapOf<String, StoredChatMessage>()

    @Synchronized fun commit(message: StoredChatMessage): ChatMessageStoreResult {
        if (messages.containsKey(message.messageId)) return ChatMessageStoreResult.AlreadyStored(message.messageId)
        messages[message.messageId] = message
        while (messages.size > 100) messages.remove(messages.keys.first())
        return ChatMessageStoreResult.Accepted(message)
    }

    @Synchronized fun snapshot(): List<StoredChatMessage> = messages.values.toList()
}

/** Process-wide canonical chat repository shared by service and visible UI producers. */
internal object CanonicalChatMessageStore {
    private val delegate = ChatMessageDeliveryStore()
    fun commit(message: StoredChatMessage): ChatMessageStoreResult = delegate.commit(message)
    fun snapshot(): List<StoredChatMessage> = delegate.snapshot()
}

/** Pre-final model suggestions cannot own app launch or visible-target opening. */
internal object PreFinalPhoneToolOwnershipPolicy {
    fun mayExecute(action: String, authoritativeFinalTranscript: Boolean): Boolean =
        authoritativeFinalTranscript || action.uppercase() != "OPEN_APP"
}
