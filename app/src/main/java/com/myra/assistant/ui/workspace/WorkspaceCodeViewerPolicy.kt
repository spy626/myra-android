package com.myra.assistant.ui.workspace

/** Pure preview and code-copy rules; never changes generated source. */
internal object WorkspaceCodeViewerPolicy {
    fun initialPreview(block: WorkspaceCodeBlocks.Part.Code, requested: Boolean): Boolean =
        requested && WorkspaceCodeBlocks.canPreview(block)

    fun copiedSource(block: WorkspaceCodeBlocks.Part.Code): String = block.source
}
