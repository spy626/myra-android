package com.myra.assistant.model

sealed interface AppCommand {
    data class OpenApp(val appName: String) : AppCommand
    data class CloseCurrentApp(val requestedName: String? = null) : AppCommand
}
