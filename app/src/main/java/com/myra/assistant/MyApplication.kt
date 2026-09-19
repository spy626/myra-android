package com.myra.assistant

import android.app.Application
import com.myra.assistant.core.AssistantController
import com.myra.assistant.diagnostics.VoicePipelineLogger

class MyApplication : Application() {
    val assistantController: AssistantController by lazy { AssistantController(this) }

    override fun onCreate() {
        super.onCreate()
        application = this
        VoicePipelineLogger.initialize(this)
    }

    companion object {
        @Volatile private var application: MyApplication? = null
        /** Application context only: never retain a Workspace Activity or voice session. */
        fun contextOrNull(): Application? = application
    }
}
