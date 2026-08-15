package com.myra.assistant.commands

import com.myra.assistant.model.AppCommand

data class Command(
    val type: CommandType,
    val target: String? = null,
    val content: String? = null,
    val sourceText: String,
    val localCommand: AppCommand? = null
)
