package com.myra.assistant.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.myra.assistant.model.AppCommand
import com.myra.assistant.service.AccessibilityHelperService
import java.util.Locale

class AppActionExecutor(private val context: Context) {
    data class Result(val message: String, val success: Boolean)

    private val knownPackages = mapOf(
        "youtube" to "com.google.android.youtube", "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android", "facebook" to "com.facebook.katana",
        "chrome" to "com.android.chrome", "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps", "google maps" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music", "netflix" to "com.netflix.mediaclient",
        "x" to "com.twitter.android", "twitter" to "com.twitter.android",
        "telegram" to "org.telegram.messenger", "snapchat" to "com.snapchat.android",
        "play store" to "com.android.vending", "settings" to "com.android.settings",
        "phonepe" to "com.phonepe.app", "gpay" to "com.google.android.apps.nbu.paisa.user",
        "google pay" to "com.google.android.apps.nbu.paisa.user", "paytm" to "net.one97.paytm",
        "amazon" to "in.amazon.mShop.android.shopping", "flipkart" to "com.flipkart.android",
        "discord" to "com.discord", "linkedin" to "com.linkedin.android"
    )

    fun execute(command: AppCommand): Result = when (command) {
        is AppCommand.OpenApp -> openApp(command.appName)
        is AppCommand.CloseCurrentApp -> closeCurrentApp()
    }

    private fun openApp(rawName: String): Result {
        val name = normalize(rawName)
        val pm = context.packageManager
        val launch = knownPackages[name]?.let(pm::getLaunchIntentForPackage) ?: findInstalledApp(name, pm)
        if (launch == null) return Result("I couldn't find $rawName on this phone.", false)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch); Result("Opening $rawName.", true)
        } catch (_: Exception) { Result("$rawName is installed, but Android would not let me open it.", false) }
    }

    private fun findInstalledApp(name: String, pm: PackageManager): Intent? {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(query, PackageManager.MATCH_ALL)
        val match = matches.firstOrNull { normalize(it.loadLabel(pm).toString()) == name }
            ?: matches.firstOrNull { normalize(it.loadLabel(pm).toString()).let { label -> label.contains(name) || name.contains(label) } }
        return match?.activityInfo?.let {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(it.packageName, it.name)
        }
    }

    private fun closeCurrentApp(): Result {
        val service = AccessibilityHelperService.instance
        if (service == null || !AccessibilityHelperService.isEnabled(context)) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Result("Enable MYRA Accessibility, then try the close command again.", false)
        }
        return if (service.closeCurrentApp()) Result("Moved the current app to the background.", true)
        else Result("Android could not close the current app.", false)
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ").trim()
}
