package com.myra.assistant.agent

enum class TextComposeState { FIELD_CONTEXT_OPEN, EDITING, READY_TO_SEND }

data class TextComposeSnapshot(
    val packageName: String,
    val windowId: Int?,
    val generation: Long,
    val fieldIdentity: String? = null,
    val draft: String = "",
    val state: TextComposeState = TextComposeState.FIELD_CONTEXT_OPEN
)

/**
 * General, short-lived text-field ownership. It never consumes conversation and never infers
 * Send: only the unified turn owner may call setDraft/send after classifying an explicit action.
 */
class TextComposeSession {
    private var value: TextComposeSnapshot? = null
    fun snapshot(): TextComposeSnapshot? = value
    fun open(packageName: String, windowId: Int?, generation: Long) {
        value = TextComposeSnapshot(packageName, windowId, generation)
    }
    fun setDraft(packageName: String, windowId: Int?, generation: Long, fieldIdentity: String, draft: String): Boolean {
        if (!owns(packageName, windowId, generation) || draft.isBlank()) return false
        value = value!!.copy(fieldIdentity = fieldIdentity, draft = draft, state = TextComposeState.READY_TO_SEND)
        return true
    }
    fun canSend(packageName: String, windowId: Int?, generation: Long): Boolean =
        owns(packageName, windowId, generation) && value?.state == TextComposeState.READY_TO_SEND && value?.draft?.isNotBlank() == true
    fun cancel() { value = null }
    fun invalidateUnless(packageName: String, windowId: Int?, generation: Long) {
        if (!owns(packageName, windowId, generation)) value = null
    }
    private fun owns(packageName: String, windowId: Int?, generation: Long): Boolean {
        val current = value ?: return false
        return current.packageName == packageName && current.windowId == windowId && current.generation == generation
    }
}
