package com.myra.assistant.ai

/** Reassembles Gemini transcription deltas without inventing word boundaries. */
internal object LiveTranscriptAssembler {
    fun append(target: StringBuilder, delta: String) {
        if (delta.isEmpty()) return
        val incoming = if (target.isEmpty()) delta.trimStart() else delta
        if (incoming.isEmpty()) return

        // Some preview models may revise a hypothesis by sending the complete text so
        // far. Replace that cumulative hypothesis instead of duplicating it.
        val existing = target.toString()
        val comparableIncoming = incoming.trimStart()
        if (existing.isNotEmpty() &&
            comparableIncoming.length > existing.length &&
            comparableIncoming.startsWith(existing)
        ) {
            target.clear()
            target.append(comparableIncoming)
            return
        }

        // For true deltas, preserve the API's exact leading space. Trimming every chunk
        // and adding our own space corrupts words split as "Mun" + "nar".
        target.append(incoming)
    }
}
