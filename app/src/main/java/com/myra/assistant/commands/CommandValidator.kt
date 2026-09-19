package com.myra.assistant.commands

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.myra.assistant.core.AssistantResult

class CommandValidator(private val context: Context) {
    fun validate(command: Command): AssistantResult? {
        if (command.type == CommandType.UNKNOWN) return AssistantResult(false, false, "UNKNOWN", spokenMessage = "Zopy, ye command mujhe clear nahi hui. Thoda seedha bolkar dobara try karo.")
        if (command.type in setOf(CommandType.FLASHLIGHT_ON, CommandType.FLASHLIGHT_OFF) && !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            return AssistantResult(false, true, command.type.name, spokenMessage = "Zopy, is phone mein flashlight available nahi hai.")
        }
        if (command.type == CommandType.REPLY_WHATSAPP && command.content.isNullOrBlank()) {
            return AssistantResult(false, true, command.type.name, command.target, "Kya message bhejna hai, Zopy?")
        }
        return null
    }

    fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
