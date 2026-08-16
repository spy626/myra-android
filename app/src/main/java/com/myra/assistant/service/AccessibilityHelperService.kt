package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        // "Close" cannot force-stop another Android app. Pause active media first,
        // then leave the foreground app and return to MYRA.
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
        val movedToHome = performGlobalAction(GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({
            val openMyra = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { startActivity(openMyra) }
        }, 100L)
        return movedToHome
    }

    fun clickFirstYouTubeVideo(): Boolean = clickVisibleYouTubeVideo(afterPlayer = false)

    fun clickNextYouTubeVideo(): Boolean = clickVisibleYouTubeVideo(afterPlayer = true)

    private fun clickVisibleYouTubeVideo(afterPlayer: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!root.packageName?.toString().orEmpty().equals(YOUTUBE_PACKAGE, ignoreCase = true)) return false
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val minimumTop = if (afterPlayer) (screenHeight * 0.30f).toInt() else (screenHeight * 0.10f).toInt()
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectVideoCandidates(root, screenWidth, screenHeight, minimumTop, candidates)
        return candidates.sortedBy { it.first }.firstOrNull()?.second
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun collectVideoCandidates(
        node: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        minimumTop: Int,
        output: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (node.isVisibleToUser) {
            val label = listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName)
                .joinToString(" ")
            val contextLabel = nodeContextLabel(node)
            if (looksLikeVideoCard(label) && !AD_SIGNAL.containsMatchIn(contextLabel)) {
                var clickable: AccessibilityNodeInfo? = node
                repeat(4) {
                    if (clickable?.isClickable == true) return@repeat
                    clickable = clickable?.parent
                }
                clickable?.takeIf { it.isClickable && it.isVisibleToUser }?.let { target ->
                    val bounds = Rect()
                    target.getBoundsInScreen(bounds)
                    if (bounds.top >= minimumTop && bounds.bottom <= screenHeight &&
                        bounds.width() >= (screenWidth * 0.30f).toInt() &&
                        bounds.height() >= (screenHeight * 0.015f).toInt()
                    ) {
                        output += bounds.top to target
                    }
                }
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let {
                collectVideoCandidates(it, screenWidth, screenHeight, minimumTop, output)
            }
        }
    }

    private fun nodeContextLabel(start: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        var current: AccessibilityNodeInfo? = start
        repeat(4) {
            current?.let { node ->
                listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName)
                    .forEach { parts += it.toString() }
            }
            current = current?.parent
        }
        return parts.joinToString(" ").lowercase()
    }

    private fun looksLikeVideoCard(label: String): Boolean {
        val clean = label.lowercase()
        if (clean.isBlank() || NON_VIDEO_CONTROLS.containsMatchIn(clean)) return false
        return VIDEO_CARD_SIGNAL.containsMatchIn(clean)
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private val VIDEO_CARD_SIGNAL = Regex("(?:video[_\\s]*(?:title|thumbnail)|thumbnail|\\bviews?\\b|watching|premiere|\\blive\\b|ago|\\d{1,2}:\\d{2})", RegexOption.IGNORE_CASE)
        private val AD_SIGNAL = Regex("(?:\\bsponsored\\b|\\badvertisement\\b|\\bad\\b|\\binstall\\b|learn more|visit advertiser|google play)", RegexOption.IGNORE_CASE)
        private val NON_VIDEO_CONTROLS = Regex("^(?:home|shorts|subscriptions|you|library|comments?|share|like|dislike|download|save|settings)$", RegexOption.IGNORE_CASE)

        @Volatile var instance: AccessibilityHelperService? = null
            private set
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, AccessibilityHelperService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.equals(component, ignoreCase = true) }
        }
    }
}
