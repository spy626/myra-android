package com.myra.assistant.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.myra.assistant.service.MyraVoiceService

class ServiceCoordinator(private val context: Context) {
    fun startVoiceService() = ContextCompat.startForegroundService(context, Intent(context, MyraVoiceService::class.java).setAction(MyraVoiceService.ACTION_START))
    fun stopVoiceService() = context.startService(Intent(context, MyraVoiceService::class.java).setAction(MyraVoiceService.ACTION_STOP))
}
