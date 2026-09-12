package com.myra.assistant

import android.app.Application
import com.myra.assistant.core.AssistantController
import com.myra.assistant.diagnostics.VoicePipelineLogger

class MyApplication : Application() {
    val assistantController: AssistantController by lazy { AssistantController(this) }

    override fun onCreate() {
        super.onCreate()
        VoicePipelineLogger.initialize(this)
    }
}
