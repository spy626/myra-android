package com.myra.assistant.security

import android.content.Context
import com.myra.assistant.data.memory.JarvisSimpleMemoryRuntime
import org.json.JSONArray
import org.json.JSONObject

class ActionAuditLogger(context: Context) {
    private val prefs = context.getSharedPreferences("myra_action_history", Context.MODE_PRIVATE)
    fun record(action: String, target: String?, success: Boolean, verified: Boolean) {
        val now = System.currentTimeMillis()
        val history = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrDefault(JSONArray())
        history.put(JSONObject().put("time", now).put("action", action).put("target", target).put("success", success).put("verified", verified))
        while (history.length() > 100) history.remove(0)
        prefs.edit().putString("entries", history.toString()).apply()

        // Keep the old audit history intact, and additionally persist JARVIS-style command logs.
        JarvisSimpleMemoryRuntime.recordCommand(
            rawCommand = listOfNotNull(action.takeIf { it.isNotBlank() }, target?.takeIf { it.isNotBlank() }).joinToString(" "),
            intentType = action,
            resultText = if (success) "Action completed" else "Action failed",
            success = success,
            verified = verified
        )
    }
}
