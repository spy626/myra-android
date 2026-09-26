package com.myra.assistant.ui.workspace

/** Local attachment classification. Audio/video may be selected locally but are fail-closed
 * until a provider route explicitly supports them. */
internal object WorkspaceAttachmentPolicy {
    enum class Kind { IMAGE, TEXT, AUDIO, VIDEO, UNSUPPORTED }

    fun kind(mime: String): Kind = when {
        mime.startsWith("image/") -> Kind.IMAGE
        mime in setOf(
            "text/plain",
            "text/html",
            "text/css",
            "application/json",
            "application/javascript",
        ) -> Kind.TEXT
        mime.startsWith("audio/") -> Kind.AUDIO
        mime.startsWith("video/") -> Kind.VIDEO
        else -> Kind.UNSUPPORTED
    }

    fun maxBytes(kind: Kind): Long = when (kind) {
        // Source photos are normalized into LYRA's <=2 MB outbound JPEG boundary.
        Kind.IMAGE -> 30_000_000L
        Kind.TEXT -> 3_000L
        Kind.AUDIO, Kind.VIDEO -> 50_000_000L
        Kind.UNSUPPORTED -> 0L
    }

    fun sendableNow(kind: Kind): Boolean =
        kind == Kind.IMAGE || kind == Kind.TEXT

    fun label(kind: Kind): String = when (kind) {
        Kind.IMAGE -> "Photo"
        Kind.TEXT -> "File"
        Kind.AUDIO -> "Audio"
        Kind.VIDEO -> "Video"
        Kind.UNSUPPORTED -> "Unsupported"
    }
}
