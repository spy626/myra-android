package com.myra.assistant.ui.workspace

/**
 * The provider's `content` is not automatically safe to display as speech. Adapted from
 * AIRI's separation of visible speech and reasoning, but only for completed Chat JSON:
 * never stream partial reasoning, guess missing boundaries, or make a second AI call.
 */
internal object WorkspaceChatVisibleReply {
    // A well-formed reasoning block can be removed without rewriting visible speech.
    private val completeBlock = Regex(
        """(?is)<\s*(think|reasoning|analysis)\s*>[\s\S]*?<\s*/\s*\1\s*>""")
    // A closing tag without an opener means the preceding text could be private reasoning.
    // Incomplete openers and special model-control tokens are unsafe as well.
    private val strayMarker = Regex(
        """(?is)<\s*/?\s*(?:think|reasoning|analysis)\b|<\|(?:im_start|im_end|endoftext|eot_id|start_header_id|end_header_id)[^>]*""")

    fun sanitize(completedText: String): String {
        require(completedText.length in 1..WorkspaceConversationStore.MAX_MESSAGE_LENGTH) {
            "LYRA received an empty or oversized reply; nothing saved."
        }
        val speech = completeBlock.replace(completedText, " ").trim()
        require(!strayMarker.containsMatchIn(speech)) {
            "LYRA received an incomplete reasoning marker; reply not saved. Tap Retry if needed. No automatic resend."
        }
        require(speech.isNotBlank()) {
            "LYRA received no visible answer after reasoning; reply not saved. Tap Retry if needed."
        }
        return speech
    }
}
