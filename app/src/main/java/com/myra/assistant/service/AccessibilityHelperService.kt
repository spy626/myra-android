package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.graphics.Rect
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.myra.assistant.ui.main.MainActivity
import java.util.Locale

class AccessibilityHelperService : AccessibilityService() {
    private var currentVideoQuery: String? = null
    private var previousVideoQuery: String? = null
    private var pendingHistoryRestoreQuery: String? = null
    private var lastScrollDown = true
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

    fun takeScreenshot(): Boolean = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    fun openYouTubeShorts(): Boolean = clickNavigationTarget(
        YOUTUBE_PACKAGE,
        setOf("shorts", "youtube shorts")
    )

    fun openInstagramReels(): Boolean = clickNavigationTarget(
        INSTAGRAM_PACKAGE,
        setOf("reels", "reel")
    )

    private fun clickNavigationTarget(packageName: String, labels: Set<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!root.packageName?.toString().orEmpty().equals(packageName, ignoreCase = true)) return false
        fun inspect(node: AccessibilityNodeInfo): Boolean {
            val values = listOfNotNull(node.text, node.contentDescription)
                .map { it.toString().trim().lowercase(Locale.ROOT) }
            if (node.isVisibleToUser && values.any { value -> labels.any { value == it || value.startsWith("$it,") } }) {
                var target: AccessibilityNodeInfo? = node
                repeat(4) {
                    val current = target ?: return@repeat
                    if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    target = current.parent
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { if (inspect(it)) return true }
            }
            return false
        }
        return inspect(root)
    }

    fun clickFirstYouTubeVideo(): Boolean {
        if (clickVisibleYouTubeVideo(afterPlayer = false)) return true
        return scrollThenClickVideo(afterPlayer = false, remainingScrolls = 2)
    }

    fun clickNextYouTubeVideo(): Boolean {
        if (clickVisibleYouTubeVideo(afterPlayer = true)) return true
        return scrollThenClickVideo(afterPlayer = true, remainingScrolls = 2)
    }

    fun scrollYouTube(down: Boolean?): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!root.packageName?.toString().orEmpty().equals(YOUTUBE_PACKAGE, ignoreCase = true)) return false
        val resolvedDown = down ?: lastScrollDown
        lastScrollDown = resolvedDown
        val accessibilityAction = if (resolvedDown) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        fun scrollNode(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && node.isScrollable && node.performAction(accessibilityAction)) return true
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { if (scrollNode(it)) return true }
            }
            return false
        }
        if (scrollNode(root)) return true
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val swipe = Path().apply {
            if (resolvedDown) {
                moveTo(width * 0.50f, height * 0.78f)
                lineTo(width * 0.50f, height * 0.32f)
            } else {
                moveTo(width * 0.50f, height * 0.32f)
                lineTo(width * 0.50f, height * 0.78f)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipe, 0L, 300L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun scrollThenClickVideo(afterPlayer: Boolean, remainingScrolls: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!root.packageName?.toString().orEmpty().equals(YOUTUBE_PACKAGE, ignoreCase = true)) return false

        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val swipe = Path().apply {
            moveTo(width * 0.50f, height * 0.84f)
            lineTo(width * 0.50f, height * 0.38f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipe, 0L, 320L))
            .build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!clickVisibleYouTubeVideo(afterPlayer) && remainingScrolls > 1) {
                        scrollThenClickVideo(afterPlayer, remainingScrolls - 1)
                    }
                }, 450L)
            }
        }, null)
    }

    private fun clickVisibleYouTubeVideo(
        afterPlayer: Boolean,
        selectionIndex: Int = 0
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!root.packageName?.toString().orEmpty().equals(YOUTUBE_PACKAGE, ignoreCase = true)) return false
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        // On the watch page the current video's title/Description target is in the
        // upper half. Recommendations begin below it, even when an ad banner is
        // inserted first. This boundary lets us use reliable title/view metadata
        // without ever selecting the current title.
        val minimumTop = if (afterPlayer) (screenHeight * 0.50f).toInt() else (screenHeight * 0.10f).toInt()
        val outgoingQuery = if (afterPlayer) {
            currentVideoQuery ?: findCurrentVideoQuery(root, screenHeight)
        } else null
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectVideoCandidates(root, screenWidth, screenHeight, minimumTop, afterPlayer, candidates)
        val uniqueCandidates = candidates.sortedBy { it.first }
            .distinctBy { (_, node) ->
                // YouTube exposes one history card through thumbnail, title and
                // metadata nodes with different bounds. Prefer the extracted title
                // as the stable identity so a single video is counted only once.
                extractVideoSearchQuery(node)
                    ?.lowercase()
                    ?.replace(Regex("\\s+"), " ")
                    ?: run {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        "row:${bounds.centerY() / (screenHeight * 0.12f).toInt().coerceAtLeast(1)}"
                    }
            }
        val target = uniqueCandidates.getOrNull(selectionIndex)?.second
        val clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (clicked) {
            val restored = pendingHistoryRestoreQuery
            val selectedQuery = restored ?: target?.let(::extractVideoSearchQuery)
            if (afterPlayer) {
                previousVideoQuery = outgoingQuery
                currentVideoQuery = selectedQuery
            } else {
                currentVideoQuery = selectedQuery
            }
            if (restored != null) pendingHistoryRestoreQuery = null
            watchForSkippableYouTubeAd()
        }
        return clicked
    }

    private fun findCurrentVideoQuery(root: AccessibilityNodeInfo, screenHeight: Int): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        fun inspect(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.top >= (screenHeight * 0.12f).toInt() &&
                    bounds.top < (screenHeight * 0.50f).toInt()
                ) {
                    val query = extractVideoSearchQuery(node)
                    if (query != null &&
                        !CURRENT_VIDEO_UI_SIGNAL.containsMatchIn(query)
                    ) candidates += bounds.top to query
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::inspect)
            }
        }
        inspect(root)
        return candidates.sortedByDescending { it.first }.firstOrNull()?.second
    }

    private fun watchForSkippableYouTubeAd(attempt: Int = 0) {
        if (attempt >= SKIP_AD_WATCH_ATTEMPTS) return
        Handler(Looper.getMainLooper()).postDelayed({
            val root = rootInActiveWindow
            if (root == null ||
                !root.packageName?.toString().orEmpty().equals(YOUTUBE_PACKAGE, ignoreCase = true)
            ) return@postDelayed

            if (!clickYouTubeSkipAd(root)) {
                watchForSkippableYouTubeAd(attempt + 1)
            }
        }, SKIP_AD_POLL_INTERVAL_MS)
    }

    private fun clickYouTubeSkipAd(node: AccessibilityNodeInfo): Boolean {
        if (node.isVisibleToUser) {
            val label = listOfNotNull(node.text, node.contentDescription)
                .joinToString(" ")
                .trim()
            if (SKIP_AD_SIGNAL.matches(label)) {
                var clickable: AccessibilityNodeInfo? = node
                repeat(5) {
                    if (clickable?.isClickable == true) return@repeat
                    clickable = clickable?.parent
                }
                if (clickable?.isClickable == true &&
                    clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                ) return true
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { if (clickYouTubeSkipAd(it)) return true }
        }
        return false
    }

    private fun collectVideoCandidates(
        node: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        minimumTop: Int,
        afterPlayer: Boolean,
        output: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (node.isVisibleToUser) {
            val label = listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName)
                .joinToString(" ")
            val contextLabel = nodeContextLabel(node)
            if (looksLikeVideoCard(label, afterPlayer) && !AD_SIGNAL.containsMatchIn(contextLabel)) {
                var clickable: AccessibilityNodeInfo? = node
                repeat(8) {
                    if (clickable?.isClickable == true) return@repeat
                    clickable = clickable?.parent
                }
                clickable?.takeIf { it.isClickable && it.isVisibleToUser }?.let { target ->
                    val bounds = Rect()
                    target.getBoundsInScreen(bounds)
                    // YouTube commonly leaves the next organic card partly below the
                    // viewport when a sponsored banner is present. A visible top edge
                    // is sufficient for Accessibility ACTION_CLICK.
                    if (bounds.top >= minimumTop && bounds.top < (screenHeight * 0.94f).toInt() &&
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
                collectVideoCandidates(it, screenWidth, screenHeight, minimumTop, afterPlayer, output)
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

    private fun looksLikeVideoCard(label: String, afterPlayer: Boolean): Boolean {
        val clean = label.lowercase()
        if (clean.isBlank() || NON_VIDEO_CONTROLS.containsMatchIn(clean)) return false
        // Modern YouTube layouts often expose cards only through title/view metadata,
        // not a thumbnail resource id. Screen position separates watch-page
        // recommendations from the current title, while the ad-context filter removes
        // sponsored cards.
        return VIDEO_LIST_SIGNAL.containsMatchIn(clean)
    }

    fun openPreviousYouTubeVideo(): Boolean {
        val fallbackQuery = previousVideoQuery?.takeIf { it.isNotBlank() }
        val returnQuery = currentVideoQuery
        previousVideoQuery = returnQuery

        val historyIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(YOUTUBE_HISTORY_URL)
        ).apply {
            setPackage(YOUTUBE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return runCatching {
            startActivity(historyIntent)
            clickPreviousFromHistoryWhenReady(attempt = 0, fallbackQuery = fallbackQuery)
            true
        }.getOrDefault(false)
    }

    private fun clickPreviousFromHistoryWhenReady(attempt: Int, fallbackQuery: String?) {
        Handler(Looper.getMainLooper()).postDelayed({
            // Watch History is newest-first: index 0 is the video that just played,
            // and index 1 is the actual previous video. Candidate de-duplication keeps
            // title, thumbnail and metadata nodes from counting as separate videos.
            if (clickVisibleYouTubeVideo(afterPlayer = false, selectionIndex = 1)) {
                return@postDelayed
            }
            if (attempt < 6) {
                clickPreviousFromHistoryWhenReady(attempt + 1, fallbackQuery)
            } else {
                fallbackQuery?.let(::openPreviousBySavedTitle)
            }
        }, if (attempt == 0) 2_000L else 650L)
    }

    private fun openPreviousBySavedTitle(query: String) {
        pendingHistoryRestoreQuery = query
        val searchIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        ).apply {
            setPackage(YOUTUBE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching {
            startActivity(searchIntent)
            clickFirstVideoWhenReady(attempt = 0)
        }.onFailure { pendingHistoryRestoreQuery = null }
    }

    private fun clickFirstVideoWhenReady(attempt: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (clickVisibleYouTubeVideo(afterPlayer = false)) return@postDelayed
            if (attempt < 5) clickFirstVideoWhenReady(attempt + 1)
            else pendingHistoryRestoreQuery = null
        }, if (attempt == 0) 1_100L else 550L)
    }

    private fun extractVideoSearchQuery(node: AccessibilityNodeInfo): String? {
        val values = mutableListOf<String>()
        fun collect(current: AccessibilityNodeInfo, depth: Int) {
            listOfNotNull(current.text, current.contentDescription)
                .mapTo(values) { it.toString().trim() }
            if (depth >= 4) return
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let { collect(it, depth + 1) }
            }
        }
        collect(node, 0)
        return values.asSequence()
            .filter { it.length in 6..180 }
            .filterNot { AD_SIGNAL.containsMatchIn(it) || NON_VIDEO_CONTROLS.matches(it) }
            .map { value ->
                value.split(
                    Regex("(?:\\s+[•·]\\s+|\\s+\\d[\\d,.]*\\s+views?\\b|\\s+\\d+\\s+(?:hours?|days?|weeks?|months?|years?)\\s+ago\\b)",
                        RegexOption.IGNORE_CASE)
                ).first().trim()
            }
            .firstOrNull { it.length >= 6 && !Regex("^\\d{1,2}:\\d{2}$").matches(it) }
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val YOUTUBE_HISTORY_URL = "https://www.youtube.com/feed/history"
        private const val SKIP_AD_POLL_INTERVAL_MS = 500L
        private const val SKIP_AD_WATCH_ATTEMPTS = 60
        private val SKIP_AD_SIGNAL = Regex(
            "^(?:skip\\s+ads?|skip\\s+advertisement|विज्ञापन\\s+छोड़ें|विज्ञापन\\s+स्किप\\s+करें)$",
            RegexOption.IGNORE_CASE
        )
        private val CURRENT_VIDEO_UI_SIGNAL = Regex("^(?:youtube|video player|comments?|subscribe|share|like|dislike|more actions)$", RegexOption.IGNORE_CASE)
        private val VIDEO_LIST_SIGNAL = Regex("(?:video[_\\s]*(?:title|thumbnail)|thumbnail|\\bviews?\\b|watching|premiere|\\blive\\b|ago|\\d{1,2}:\\d{2})", RegexOption.IGNORE_CASE)
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
