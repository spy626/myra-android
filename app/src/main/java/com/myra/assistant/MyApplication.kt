package com.myra.assistant

import android.app.Application
import com.myra.assistant.core.AssistantController

class MyApplication : Application() {
    val assistantController: AssistantController by lazy { AssistantController(this) }
}
