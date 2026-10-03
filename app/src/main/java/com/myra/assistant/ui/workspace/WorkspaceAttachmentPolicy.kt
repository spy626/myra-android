package com.myra.assistant.ui.workspace

/** Bounded attachment families; audio/native video require a compatible zero-priced model.
 * Selecting a file alone never guarantees the free provider can interpret its modality. */
internal object WorkspaceAttachmentPolicy {
    enum class Kind { IMAGE, TEXT, AUDIO, VIDEO, UNSUPPORTED }

    fun kind(mime: String): Kind = when {
        mime.startsWith("image/") -> Kind.IMAGE
        mime in setOf(
            "text/plain",
            "text/html",
            "text/css",
            "text/markdown",
            "text/x-markdown",
            "application/json",
            "application/javascript",
        ) -> Kind.TEXT
        mime.startsWith("audio/") -> Kind.AUDIO
        mime.startsWith("video/") -> Kind.VIDEO
        else -> Kind.UNSUPPORTED
    }

    fun maxBytes(kind: Kind): Long = when (kind) {
        // Source photos are normalized to the ten-image outbound byte budget.
        Kind.IMAGE -> 30_000_000L
        Kind.TEXT -> 3_000L
        Kind.AUDIO -> WorkspaceMediaLimits.MAX_AUDIO_BYTES.toLong()
        Kind.VIDEO -> WorkspaceMediaLimits.MAX_NATIVE_VIDEO_BYTES.toLong()
        Kind.UNSUPPORTED -> 0L
    }

    fun sendableNow(kind: Kind): Boolean =
        kind == Kind.IMAGE || kind == Kind.TEXT || kind == Kind.VIDEO || kind == Kind.AUDIO

    fun label(kind: Kind): String = when (kind) {
        Kind.IMAGE -> "Photo"
        Kind.TEXT -> "File"
        Kind.AUDIO -> "Audio"
        Kind.VIDEO -> "Video"
        Kind.UNSUPPORTED -> "Unsupported"
    }
}
