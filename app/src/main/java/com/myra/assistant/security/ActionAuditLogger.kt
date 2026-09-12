package com.myra.assistant.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ActionAuditLogger(context: Context) {
    private val prefs = context.getSharedPreferences("myra_action_history", Context.MODE_PRIVATE)
    fun record(action: String, target: String?, success: Boolean, verified: Boolean) {
        val history = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrDefault(JSONArray())
        history.put(JSONObject().put("time", System.currentTimeMillis()).put("action", action).put("target", target).put("success", success).put("verified", verified))
        while (history.length() > 100) history.remove(0)
        prefs.edit().putString("entries", history.toString()).apply()
    }
}
