package com.myra.assistant.commands

import com.myra.assistant.core.AssistantResult
import com.myra.assistant.phone.AppActionExecutor

class CommandExecutor(private val actions: AppActionExecutor) {
    fun execute(command: Command): AssistantResult = actions.executeStructured(command)
}
