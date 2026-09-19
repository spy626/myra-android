package com.myra.assistant.ui.workspace

/** Pure UI bounds and data-preservation rules; never changes generated source. */
internal object WorkspaceCodeViewerPolicy {
    const val MAX_INLINE_LINES = 14

    fun needsCompactCard(source: String): Boolean = source.count { it == '\n' } + 1 > MAX_INLINE_LINES

    fun initialPreview(block: WorkspaceCodeBlocks.Part.Code, requested: Boolean): Boolean =
        requested && WorkspaceCodeBlocks.canPreview(block)

    fun copiedSource(block: WorkspaceCodeBlocks.Part.Code): String = block.source
}
