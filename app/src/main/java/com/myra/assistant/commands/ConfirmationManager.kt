package com.myra.assistant.commands

class ConfirmationManager {
    fun requiresConfirmation(command: Command): Boolean = command.type in emptySet<CommandType>()
}
