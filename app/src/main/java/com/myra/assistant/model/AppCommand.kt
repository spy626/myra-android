package com.myra.assistant.model

sealed interface AppCommand {
    data class OpenApp(val appName: String) : AppCommand
    data class CloseCurrentApp(val requestedName: String? = null) : AppCommand
    data class SearchYouTube(val query: String) : AppCommand
    data object RepeatYouTubeSearch : AppCommand
    data class DeepResearch(val query: String?) : AppCommand
    data class ReplyWhatsApp(val sender: String?, val message: String) : AppCommand
    data object QueryWhatsAppMessages : AppCommand
}
