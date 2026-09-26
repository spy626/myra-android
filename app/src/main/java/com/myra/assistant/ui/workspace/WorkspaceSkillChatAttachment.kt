package com.myra.assistant.ui.workspace

/** UI-only contract for the simple Chat skill entry point. */
internal object WorkspaceSkillChatAttachment {
    const val CREATE_SKILL_PROMPT = "Create a skill that "

    data class Verified(
        val name: String,
        val size: Long,
    )

    fun validate(name: String, size: Long): Verified {
        require(name == "SKILL.md") { "Choose the exact SKILL.md file" }
        require(size in 1L..WorkspaceSkillImportPreview.MAX_FILE_BYTES.toLong()) {
            "SKILL.md is empty or exceeds the local skill import limit"
        }
        return Verified(name, size)
    }
}
