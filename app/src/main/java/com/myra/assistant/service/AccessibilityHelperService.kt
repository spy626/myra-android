package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.myra.assistant.ui.main.MainActivity

class AccessibilityHelperService : AccessibilityService() {
    override fun onServiceConnected() { instance = this; super.onServiceConnected() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    fun returnToMyra(): Boolean {
        // Put the foreground app in the background first. Starting MYRA directly can be
        // blocked by Android's background-activity rules on some phones; an enabled
        // accessibility service is allowed to complete this user-requested navigation.
        val movedToHome = performGlobalAction(GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({
            val openMyra = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { startActivity(openMyra) }
        }, 180L)
        return movedToHome
    }

    companion object {
        @Volatile var instance: AccessibilityHelperService? = null
            private set
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, AccessibilityHelperService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.equals(component, ignoreCase = true) }
        }
    }
}
