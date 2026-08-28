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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.screen.VisibleScreenElement
import com.myra.assistant.screen.ScreenCaptureService
import com.myra.assistant.screen.ScreenShareState
import java.util.Locale

class AccessibilityHelperService : AccessibilityService() {
    private var currentVideoQuery: String? = null
    private var previousVideoQuery: String? = null
    private var pendingHistoryRestoreQuery: String? = null
    private var lastScrollDown = true
    private var screenOverlay: View? = null
    private var overlayPanel: View? = null
    private var overlayState: ScreenShareState = ScreenShareState.IDLE
    override fun onServiceConnected() { instance = this; super.onServiceConnected(); updateScreenVisionOverlay(ScreenCaptureService.currentState) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val reason = when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "accessibility_scroll"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "accessibility_window_state"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "accessibility_window_content"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "accessibility_text_changed"
            else -> null
        }
        if (reason != null) ScreenCaptureService.markScreenDirty(reason)
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() { hideScreenVisionOverlay(); if (instance === this) instance = null; super.onDestroy() }

    fun updateScreenVisionOverlay(state: ScreenShareState) {
        Handler(Looper.getMainLooper()).post {
            overlayState = state
            if (state !in setOf(ScreenShareState.ACTIVE, ScreenShareState.PAUSED, ScreenShareState.RESUMING)) {
                hideScreenVisionOverlay(); return@post
            }
            if (screenOverlay == null) showScreenVisionOverlay()
            (screenOverlay as? TextView)?.text = if (state == ScreenShareState.PAUSED) "▶" else "◉"
        }
    }

    private fun showScreenVisionOverlay() {
        if (screenOverlay != null) return
        val window = getSystemService(WINDOW_SERVICE) as WindowManager
        val bubble = TextView(this).apply {
            text = "◉"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(31, 108, 63)); setStroke(2, Color.rgb(157, 234, 170)) }
        }
        val size = (52 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = resources.displayMetrics.widthPixels - size - 18; y = resources.displayMetrics.heightPixels / 3
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt(); moved = moved || kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12
                    params.x = (startX + dx).coerceIn(0, resources.displayMetrics.widthPixels - size)
                    params.y = (startY + dy).coerceIn(0, resources.displayMetrics.heightPixels - size)
                    runCatching { window.updateViewLayout(bubble, params) }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggleOverlayPanel(params); true }
                else -> false
            }
        }
        runCatching { window.addView(bubble, params); screenOverlay = bubble }
    }

    private fun toggleOverlayPanel(bubbleParams: WindowManager.LayoutParams) {
        if (overlayPanel != null) { hideOverlayPanel(); return }
        val window = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12, 10, 12, 10)
            background = GradientDrawable().apply { cornerRadius = 18f; setColor(Color.rgb(16, 31, 22)); setStroke(2, Color.rgb(109, 201, 125)) }
        }
        fun action(label: String, run: () -> Unit) = TextView(this).apply {
            text = label; textSize = 15f; setTextColor(Color.WHITE); setPadding(20, 14, 20, 14); setOnClickListener { run(); hideOverlayPanel() }
        }
        panel.addView(action(if (overlayState == ScreenShareState.PAUSED) "Resume" else "Pause") {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(if (overlayState == ScreenShareState.PAUSED) ScreenCaptureService.ACTION_RESUME else ScreenCaptureService.ACTION_PAUSE))
        })
        panel.addView(action("Open LYRA") { returnToMyra() })
        panel.addView(action("Stop") { startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP)) })
        val width = (138 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = (bubbleParams.x - width).coerceAtLeast(0); y = bubbleParams.y
        }
        runCatching { window.addView(panel, params); overlayPanel = panel }
    }

    private fun hideOverlayPanel() {
        overlayPanel?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        overlayPanel = null
    }

    fun hideScreenVisionOverlay() {
        hideOverlayPanel()
        screenOverlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        screenOverlay = null
    }
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
        if (!isYouTubeRoot(root)) return false
        val resolvedDown = down ?: lastScrollDown
        lastScrollDown = resolvedDown
        val accessibilityAction = if (resolvedDown) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        // YouTube exposes horizontal chips, ad carousels and the vertical feed as
        // scrollable. Pick the largest tall node so "scroll" moves the Home feed.
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.isScrollable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() >= (screenWidth * 0.55f).toInt() &&
                    bounds.height() >= (screenHeight * 0.30f).toInt()
                ) candidates += bounds.width() * bounds.height() to node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        candidates.sortedByDescending { it.first }.forEach { (_, node) ->
            if (node.performAction(accessibilityAction)) return true
        }
        return dispatchYouTubeSwipe(resolvedDown)
    }

    fun scrollYouTubeVerified(down: Boolean?, onResult: (Boolean) -> Unit): Boolean {
        val resolvedDown = down ?: lastScrollDown
        lastScrollDown = resolvedDown
        val root = rootInActiveWindow
        if (root == null || !isYouTubeRoot(root)) {
            val launch = packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
                ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return runCatching {
                startActivity(launch)
                waitForYouTubeAndScroll(resolvedDown, 0, onResult)
                true
            }.getOrDefault(false)
        }
        performVerifiedScroll(resolvedDown, retry = true, onResult)
        return true
    }

    private fun waitForYouTubeAndScroll(
        down: Boolean,
        attempt: Int,
        onResult: (Boolean) -> Unit
    ) {
        Handler(Looper.getMainLooper()).postDelayed({
            val root = rootInActiveWindow
            if (root != null && isYouTubeRoot(root)) {
                performVerifiedScroll(down, retry = true, onResult)
            } else if (attempt < 7) {
                waitForYouTubeAndScroll(down, attempt + 1, onResult)
            } else {
                onResult(false)
            }
        }, if (attempt == 0) 900L else 350L)
    }

    private fun performVerifiedScroll(
        down: Boolean,
        retry: Boolean,
        onResult: (Boolean) -> Unit
    ) {
        val before = youtubeScreenSignature()
        // Swipe first so the feed starts moving immediately. Accessibility ACTION_SCROLL
        // can be accepted by a sponsored carousel without moving the vertical Home feed.
        if (!dispatchYouTubeSwipe(down, useRightEdge = true)) {
            onResult(false)
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            val changed = before.isNotBlank() && youtubeScreenSignature() != before
            if (changed) {
                onResult(true)
            } else if (retry && dispatchYouTubeSwipe(down, useRightEdge = false)) {
                Handler(Looper.getMainLooper()).postDelayed({
                    onResult(before.isNotBlank() && youtubeScreenSignature() != before)
                }, 420L)
            } else {
                onResult(false)
            }
        }, 380L)
    }

    private fun dispatchYouTubeSwipe(down: Boolean, useRightEdge: Boolean = false): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        // The first path stays away from sponsored-card buttons in the center. The
        // alternate path handles layouts whose right-side overlay consumes gestures.
        val x = width * if (useRightEdge) 0.86f else 0.18f
        val swipe = Path().apply {
            if (down) {
                moveTo(x, height * 0.82f)
                lineTo(x, height * 0.25f)
            } else {
                // Never begin an upward-page scroll inside YouTube's player. A downward
                // finger swipe over the player is YouTube's "minimize video" gesture.
                // Starting below the player scrolls the watch-page recommendations/title
                // back toward the top while the video remains in its normal player.
                moveTo(x, height * 0.58f)
                lineTo(x, height * 0.90f)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipe, 0L, 220L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun youtubeScreenSignature(): String {
        val root = rootInActiveWindow ?: return ""
        if (!isYouTubeRoot(root)) return ""
        val items = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                listOfNotNull(node.text, node.contentDescription).forEach {
                    val value = it.toString().trim()
                    if (value.isNotBlank()) items += "${bounds.top}:${bounds.bottom}:$value"
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        return items.distinct().sorted().joinToString("|").take(12_000)
    }

    private fun isYouTubeRoot(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString().orEmpty().equals(YOUTUBE_PACKAGE, ignoreCase = true)

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

    /** Reuses the existing YouTube accessibility candidate map; ordinal is one-based. */
    fun tapVisibleYouTubeVideo(ordinal: Int): Boolean =
        clickVisibleYouTubeVideo(afterPlayer = false, selectionIndex = (ordinal - 1).coerceAtLeast(0))

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

    /** Accessibility is the authoritative target map; Screen Vision never guesses raw coordinates. */
    fun visibleElements(limit: Int = 120): List<VisibleScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<VisibleScreenElement>()
        fun collect(node: AccessibilityNodeInfo) {
            if (result.size >= limit) return
            if (node.isVisibleToUser) {
                val label = listOfNotNull(node.text, node.contentDescription)
                    .joinToString(" ").trim().replace(Regex("\\s+"), " ")
                if (label.isNotBlank()) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.isEmpty) result += VisibleScreenElement(
                        label.take(240), bounds, findClickable(node) != null,
                        node.className?.toString().orEmpty()
                    )
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        return result.distinctBy { "${it.label.lowercase(Locale.ROOT)}:${it.bounds}" }
    }

    fun visibleScreenSummary(): String = visibleElements(80).joinToString("\n") {
        "${it.label} [${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}]${if (it.clickable) " clickable" else ""}"
    }.take(12_000)

    fun visibleScreenSignature(): String = visibleElements(100).joinToString("|") {
        "${it.label.lowercase(Locale.ROOT)}:${it.bounds.centerX()}:${it.bounds.centerY()}"
    }

    fun tapVisibleTarget(
        targetText: String?,
        position: String?,
        ordinal: Int?
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        data class Candidate(val node: AccessibilityNodeInfo, val label: String, val bounds: Rect)
        val candidates = mutableListOf<Candidate>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) {
                val clickable = findClickable(node)
                val label = listOfNotNull(node.text, node.contentDescription)
                    .joinToString(" ").trim().replace(Regex("\\s+"), " ")
                if (clickable != null && label.isNotBlank()) {
                    val bounds = Rect().also(clickable::getBoundsInScreen)
                    if (!bounds.isEmpty) candidates += Candidate(clickable, label, bounds)
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        val unique = candidates.distinctBy { "${it.label.lowercase(Locale.ROOT)}:${it.bounds}" }
        val queryWords = targetText.orEmpty().lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        val labelMatches = if (queryWords.isEmpty()) unique else unique.filter { candidate ->
            val label = candidate.label.lowercase(Locale.ROOT)
            queryWords.count(label::contains) >= maxOf(1, (queryWords.size + 1) / 2)
        }
        val positionMatches = labelMatches.filter { candidate ->
            when (position?.lowercase(Locale.ROOT)) {
                "top" -> candidate.bounds.centerY() < screenHeight * 0.40
                "bottom" -> candidate.bounds.centerY() > screenHeight * 0.60
                "left" -> candidate.bounds.centerX() < screenWidth * 0.45
                "right" -> candidate.bounds.centerX() > screenWidth * 0.55
                "center", "middle" -> candidate.bounds.centerY() in (screenHeight * 0.30).toInt()..(screenHeight * 0.70).toInt()
                else -> true
            }
        }
        val sorted = positionMatches.sortedWith(compareBy<Candidate> { it.bounds.top }.thenBy { it.bounds.left })
        val selected = if (ordinal != null && ordinal > 0) sorted.getOrNull(ordinal - 1) else {
            if (position.equals("center", true) || position.equals("middle", true)) {
                sorted.minByOrNull { kotlin.math.abs(it.bounds.centerY() - screenHeight / 2) }
            } else sorted.singleOrNull() ?: sorted.firstOrNull()
        } ?: return false
        return selected.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

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
