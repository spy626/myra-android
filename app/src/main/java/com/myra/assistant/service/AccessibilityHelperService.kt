package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.screen.VisibleScreenElement
import com.myra.assistant.screen.ScreenCaptureService
import com.myra.assistant.screen.ScreenShareState
import com.myra.assistant.screen.ScreenTargetCandidate
import com.myra.assistant.screen.ScreenTargetResolution
import com.myra.assistant.screen.ScreenTargetResolver
import com.myra.assistant.screen.ForegroundAppContext
import com.myra.assistant.screen.ForegroundActionScope
import com.myra.assistant.screen.ForegroundActionPolicy
import com.myra.assistant.screen.YouTubeVideoCandidate
import com.myra.assistant.screen.YouTubeVideoCandidatePolicy
import com.myra.assistant.screen.YouTubeSemanticCommand
import com.myra.assistant.screen.YouTubeSemanticElement
import com.myra.assistant.screen.YouTubeSemanticResolution
import com.myra.assistant.screen.YouTubeSemanticResolver
import com.myra.assistant.screen.YouTubeSemanticRole
import com.myra.assistant.screen.VisualAwarenessPreferences
import com.myra.assistant.screen.VisualObservationPolicy
import com.myra.assistant.screen.AccessibilityScreenshot
import com.myra.assistant.screen.AccessibilityVisualCache
import com.myra.assistant.screen.VisualFrameSource
import com.myra.assistant.screen.VisualScreenshotSelection
import com.myra.assistant.agent.ActivityContextStore
import com.myra.assistant.agent.ActivityObservationCoalescer
import com.myra.assistant.agent.CurrentActivityContext
import com.myra.assistant.agent.SemanticElement
import com.myra.assistant.agent.SemanticRoleClassifier
import com.myra.assistant.agent.ScreenshotReference
import com.myra.assistant.agent.UnifiedLyraAgentRuntime
import com.myra.assistant.diagnostics.VoicePipelineLogger
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.Locale
import com.myra.assistant.screen.VisualCaptureCompletionGate
import com.myra.assistant.screen.VisualScreenshotTimeoutPolicy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class VisibleTargetTapResult(
    val accepted: Boolean,
    val candidate: ScreenTargetCandidate? = null,
    val confidence: Double = 0.0,
    val resolution: String
)

data class GenericSemanticTapResult(
    val accepted: Boolean,
    val method: String,
    val resolution: String,
    val targetId: String? = null
)

data class YouTubeVideoTapResult(
    val accepted: Boolean,
    val resolution: String,
    val candidateCount: Int = 0,
    val selectedLabel: String? = null,
    val selectedBounds: Rect? = null
)

data class YouTubeSemanticActionResult(
    val accepted: Boolean,
    val resolution: String,
    val role: YouTubeSemanticRole? = null,
    val fieldIdentity: String? = null
)

class AccessibilityHelperService : AccessibilityService() {
    private val activityObservationCoalescer = ActivityObservationCoalescer()
    private var currentVideoQuery: String? = null
    private var previousVideoQuery: String? = null
    private var pendingHistoryRestoreQuery: String? = null
    private var lastScrollDown = true
    private var screenOverlay: View? = null
    private var overlayPanel: View? = null
    private var overlayState: ScreenShareState = ScreenShareState.IDLE
    private val visualAwareness by lazy { VisualAwarenessPreferences(this) }
    @Volatile private var accessibilitySnapshotAt: Long = 0L
    private var foregroundPackage: String? = null
    private var foregroundWindowId: Int? = null
    private var foregroundGeneration: Long = 0L
    private val screenWatcherHandler = Handler(Looper.getMainLooper())
    private val visualTimeoutExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "lyra-visual-timeout").apply { isDaemon = true }
    }
    private val visualProcessingExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadFactory { runnable ->
            Thread(runnable, "lyra-visual-processing").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
        }
    )
    private val screenWatcher = object : Runnable {
        override fun run() {
            // Accessibility context is the normal always-lightweight observation path.
            // MediaProjection state must not gate foreground/window awareness.
            refreshScreenContext()
            val now = android.os.SystemClock.elapsedRealtime()
            val scrolling = now - com.myra.assistant.screen.ScreenContextStore.snapshot().lastScrollAt <= 1_000L
            screenWatcherHandler.postDelayed(this, if (scrolling) 300L else 1_000L)
        }
    }
    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
        updateScreenVisionOverlay(ScreenCaptureService.currentState)
        if (screenOverlay == null) showScreenVisionOverlay()
        screenWatcherHandler.removeCallbacks(screenWatcher)
        screenWatcherHandler.post(screenWatcher)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val reason = when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "accessibility_scroll"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "accessibility_window_state"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "accessibility_window_content"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "accessibility_text_changed"
            else -> null
        }
        if (reason != null) {
            accessibilitySnapshotAt = android.os.SystemClock.elapsedRealtime()
            if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                com.myra.assistant.screen.ScreenContextStore.markScrolling(accessibilitySnapshotAt)
            }
            ScreenCaptureService.markScreenDirty(reason)
            refreshScreenContext(accessibilitySnapshotAt)
        }
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        screenWatcherHandler.removeCallbacks(screenWatcher)
        visualTimeoutExecutor.shutdownNow()
        visualProcessingExecutor.shutdownNow()
        AccessibilityVisualCache.invalidate()
        hideScreenVisionOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun updateScreenVisionOverlay(state: ScreenShareState) {
        Handler(Looper.getMainLooper()).post {
            overlayState = state
            if (screenOverlay == null) showScreenVisionOverlay()
            (screenOverlay as? TextView)?.text = if (visualAwareness.enabled) "◉" else "○"
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
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        visualAwareness.enabled = !visualAwareness.enabled
                        if (!visualAwareness.enabled) AccessibilityVisualCache.invalidate()
                        bubble.text = if (visualAwareness.enabled) "◉" else "○"
                        com.myra.assistant.diagnostics.VoicePipelineLogger.debug(
                            "visual_awareness_changed enabled=${visualAwareness.enabled} mediaProjectionState=$overlayState"
                        )
                    }
                    true
                }
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

    /**
     * Captures an in-memory Accessibility screenshot on Android 11+. This is LYRA's
     * normal visual observation path and never starts MediaProjection.
     */
    fun requestVisualScreenshot(
        expected: ForegroundAppContext,
        semanticSignature: String,
        requestToken: String,
        isCurrentRequest: () -> Boolean,
        callback: (Result<AccessibilityScreenshot>) -> Unit
    ): Boolean {
        if (!VisualObservationPolicy.mayRequestScreenshot(visualAwareness.enabled, Build.VERSION.SDK_INT)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val dispatchAt = android.os.SystemClock.elapsedRealtime()
        if (!isCurrentRequest()) {
            VoicePipelineLogger.debug("capture_dispatch_dropped requestToken=$requestToken reason=stale_before_api_call")
            return false
        }
        VoicePipelineLogger.debug(
            "captureDispatchAcknowledged requestToken=$requestToken mainLooperCaptureDispatchDelayMs=0 " +
                "captureTaskAgeMs=0 captureQueueDepth=0 captureThread=${Thread.currentThread().name}"
        )
        VoicePipelineLogger.debug("screenshotApiCalled requestToken=$requestToken at=$dispatchAt")
        takeScreenshot(Display.DEFAULT_DISPLAY, visualProcessingExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val callbackEnteredAt = android.os.SystemClock.elapsedRealtime()
                val queueDepth = (visualProcessingExecutor as? java.util.concurrent.ThreadPoolExecutor)?.queue?.size ?: 0
                VoicePipelineLogger.debug(
                    "SCREENSHOT_CALLBACK_ENTER requestToken=$requestToken visualTurnId=$requestToken " +
                        "executorName=lyra-visual-processing threadName=${Thread.currentThread().name} " +
                        "timestamp=$callbackEnteredAt queueDepth=$queueDepth taskAgeMs=${callbackEnteredAt - dispatchAt} lockWaitMs=0"
                )
                if (!isCurrentRequest()) {
                    result.hardwareBuffer.close()
                    VoicePipelineLogger.debug("screenshot_callback_received requestToken=$requestToken accepted=false reason=stale_request")
                    callback(Result.failure(IllegalStateException("stale_visual_turn")))
                    return
                }
                val current = currentForegroundContext()
                if (current == null || current.packageName != expected.packageName ||
                    current.windowId != expected.windowId || current.generation != expected.generation
                ) {
                    result.hardwareBuffer.close()
                    callback(Result.failure(IllegalStateException("stale_accessibility_screenshot")))
                    return
                }
                val buffer = result.hardwareBuffer
                val wrapStartedAt = android.os.SystemClock.elapsedRealtime()
                VoicePipelineLogger.debug("hardwareBufferToBitmapStarted requestToken=$requestToken timestamp=$wrapStartedAt")
                val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                val wrapCompletedAt = android.os.SystemClock.elapsedRealtime()
                VoicePipelineLogger.debug(
                    "hardwareBufferToBitmapCompleted requestToken=$requestToken timestamp=$wrapCompletedAt " +
                        "elapsedMs=${wrapCompletedAt - wrapStartedAt} bitmapWidth=${wrapped?.width ?: 0} bitmapHeight=${wrapped?.height ?: 0}"
                )
                val copyStartedAt = android.os.SystemClock.elapsedRealtime()
                VoicePipelineLogger.debug("bitmapCopyStarted requestToken=$requestToken timestamp=$copyStartedAt")
                val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                val copyCompletedAt = android.os.SystemClock.elapsedRealtime()
                VoicePipelineLogger.debug(
                    "bitmapCopyCompleted requestToken=$requestToken timestamp=$copyCompletedAt " +
                        "elapsedMs=${copyCompletedAt - copyStartedAt} bitmapWidth=${bitmap?.width ?: 0} bitmapHeight=${bitmap?.height ?: 0}"
                )
                buffer.close()
                if (bitmap == null) {
                    callback(Result.failure(IllegalStateException("screenshot_decode_failed")))
                    return
                }
                val resizeAt = android.os.SystemClock.elapsedRealtime()
                VoicePipelineLogger.debug("bitmapResizeStarted requestToken=$requestToken timestamp=$resizeAt")
                VoicePipelineLogger.debug("bitmapResizeCompleted requestToken=$requestToken timestamp=$resizeAt elapsedMs=0 reason=not_required")
                val encodeStartedAt = android.os.SystemClock.elapsedRealtime()
                VoicePipelineLogger.debug("jpegEncodeStarted requestToken=$requestToken timestamp=$encodeStartedAt")
                val bytes = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)
                    output.toByteArray()
                }
                val encodeCompletedAt = android.os.SystemClock.elapsedRealtime()
                VoicePipelineLogger.debug(
                    "jpegEncodeCompleted requestToken=$requestToken timestamp=$encodeCompletedAt " +
                        "elapsedMs=${encodeCompletedAt - encodeStartedAt} encodedBytes=${bytes.size}"
                )
                val capturedAt = android.os.SystemClock.elapsedRealtime()
                val screenshot = AccessibilityScreenshot(bytes, bitmap.width, bitmap.height, capturedAt,
                    current.packageName, current.windowId, current.generation)
                bitmap.recycle()
                AccessibilityVisualCache.put(screenshot, semanticSignature)
                val sceneStartedAt = android.os.SystemClock.elapsedRealtime()
                VoicePipelineLogger.debug("semanticSceneSnapshotStarted requestToken=$requestToken timestamp=$sceneStartedAt")
                ActivityContextStore.attachScreenshot(
                    ScreenshotReference(UUID.randomUUID().toString(), capturedAt, screenshot.width, screenshot.height),
                    current.packageName, current.windowId
                )
                val sceneCompletedAt = android.os.SystemClock.elapsedRealtime()
                VoicePipelineLogger.debug(
                    "semanticSceneSnapshotCompleted requestToken=$requestToken timestamp=$sceneCompletedAt " +
                        "elapsedMs=${sceneCompletedAt - sceneStartedAt}"
                )
                VoicePipelineLogger.debug(
                    "visualFrameObjectCreated requestToken=$requestToken timestamp=$sceneCompletedAt " +
                        "bitmapWidth=${screenshot.width} bitmapHeight=${screenshot.height} encodedBytes=${screenshot.bytes.size}"
                )
                callback(Result.success(screenshot))
            }

            override fun onFailure(errorCode: Int) {
                callback(Result.failure(IllegalStateException("accessibility_screenshot_error_$errorCode")))
            }
        })
        return true
    }

    /** Compatibility entry point for non-fast visual helpers. It still dispatches the
     * platform screenshot immediately and performs image work off the service thread. */
    fun requestVisualScreenshot(callback: (Result<AccessibilityScreenshot>) -> Unit): Boolean {
        val expected = currentForegroundContext() ?: return false
        val snapshot = ActivityContextStore.snapshot()
        val signature = snapshot?.takeIf {
            it.packageName == expected.packageName && it.windowId == expected.windowId && it.generation == expected.generation
        }?.visibleElements?.joinToString("|") {
            "${it.role}:${it.label.lowercase(Locale.ROOT)}:${it.centerX}:${it.centerY}:${it.actionable}"
        }.orEmpty()
        return requestVisualScreenshot(expected, signature, "compat-${android.os.SystemClock.elapsedRealtime()}", { true }, callback)
    }

    /** Select exactly one context-bound screenshot for a visual turn. */
    fun requestFreshVisualScreenshot(
        maxAgeMs: Long,
        timeoutMs: Long = VisualScreenshotTimeoutPolicy.TIMEOUT_MS,
        fallbackMaxAgeMs: Long = VisualScreenshotTimeoutPolicy.SAFE_FALLBACK_MAX_AGE_MS,
        requestToken: String = "visual-${android.os.SystemClock.elapsedRealtime()}",
        isCurrentRequest: () -> Boolean = { true },
        callback: (Result<VisualScreenshotSelection>) -> Unit
    ): Boolean {
        val current = currentForegroundContext() ?: return false
        // Never traverse the Accessibility tree before calling takeScreenshot(). On real
        // devices that synchronous traversal blocked the service/main path for 13-16s.
        val snapshot = ActivityContextStore.snapshot()
        val signature = snapshot?.takeIf {
            it.packageName == current.packageName && it.windowId == current.windowId && it.generation == current.generation
        }?.visibleElements?.joinToString("|") {
            "${it.role}:${it.label.lowercase(Locale.ROOT)}:${it.centerX}:${it.centerY}:${it.actionable}"
        }.orEmpty()
        val requestedAt = android.os.SystemClock.elapsedRealtime()
        AccessibilityVisualCache.selectFresh(
            current.packageName, current.windowId, current.generation, signature,
            requestedAt, maxAgeMs
        )?.let {
            VoicePipelineLogger.debug("screenshot_cache_fallback source=FRESH_CACHE ageMs=${requestedAt - it.screenshot.capturedAt}")
            callback(Result.success(it))
            return true
        }
        VoicePipelineLogger.debug(
            "screenshot_request_started package=${current.packageName} windowId=${current.windowId} " +
                "generation=${current.generation} timeoutMs=$timeoutMs"
        )
        val completionGate = VisualCaptureCompletionGate()
        val timeout = Runnable {
            if (!completionGate.tryComplete()) return@Runnable
            val now = android.os.SystemClock.elapsedRealtime()
            val fallback = AccessibilityVisualCache.selectFresh(
                current.packageName, current.windowId, current.generation, signature,
                now, fallbackMaxAgeMs
            )
            VoicePipelineLogger.debug(
                "screenshot_timeout elapsedMs=${now - requestedAt} cacheFallback=${fallback != null}"
            )
            if (fallback != null) {
                VoicePipelineLogger.debug("screenshot_cache_fallback source=STALE_SAFE_CACHE ageMs=${now - fallback.screenshot.capturedAt}")
                callback(Result.success(fallback))
            } else {
                callback(Result.failure(IllegalStateException("accessibility_screenshot_timeout")))
            }
        }
        val timeoutFuture = visualTimeoutExecutor.schedule(timeout, timeoutMs, TimeUnit.MILLISECONDS)
        if (!isCurrentRequest()) {
            timeoutFuture.cancel(false)
            completionGate.tryComplete()
            VoicePipelineLogger.debug("capture_dispatch_dropped requestToken=$requestToken reason=stale_before_dispatch")
            return false
        }
        VoicePipelineLogger.debug("captureDispatchAttempted requestToken=$requestToken at=$requestedAt")
        val started = requestVisualScreenshot(current, signature, requestToken, isCurrentRequest) { result ->
            val callbackAt = android.os.SystemClock.elapsedRealtime()
            if (!completionGate.tryComplete()) {
                VoicePipelineLogger.debug("screenshot_callback_received elapsedMs=${callbackAt - requestedAt} accepted=false reason=late_after_timeout")
                return@requestVisualScreenshot
            }
            timeoutFuture.cancel(false)
            VoicePipelineLogger.debug("screenshot_callback_received elapsedMs=${callbackAt - requestedAt} accepted=true success=${result.isSuccess}")
            callback(result.map { VisualScreenshotSelection(it, VisualFrameSource.ACCESSIBILITY_FRESH) })
        }
        if (!started && completionGate.tryComplete()) {
            timeoutFuture.cancel(false)
            VoicePipelineLogger.debug("screenshot_failure_reason reason=request_not_started")
            callback(Result.failure(IllegalStateException("accessibility_screenshot_not_started")))
        }
        return started
    }

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

    fun scrollYouTubeForegroundVerified(
        scope: ForegroundActionScope,
        down: Boolean?,
        onResult: (Boolean) -> Unit
    ): Boolean {
        if (!ForegroundActionPolicy.canExecute(scope, currentForegroundContext())) return false
        val root = rootInActiveWindow ?: return false
        if (!isYouTubeRoot(root)) return false
        val resolvedDown = down ?: lastScrollDown
        lastScrollDown = resolvedDown
        performVerifiedScroll(resolvedDown, retry = true) { changed ->
            val stillYouTube = ForegroundActionPolicy.canExecute(scope, currentForegroundContext())
            onResult(changed && stillYouTube)
        }
        return true
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

    /**
     * Resolves an ordinal against unique, non-ad YouTube video cards and revalidates
     * foreground ownership immediately before ACTION_CLICK.
     */
    fun resolveAndTapYouTubeVideo(
        ordinal: Int,
        scope: ForegroundActionScope
    ): YouTubeVideoTapResult {
        if (!ForegroundActionPolicy.canExecute(scope, currentForegroundContext())) {
            return YouTubeVideoTapResult(false, "stale_foreground")
        }
        val root = rootInActiveWindow ?: return YouTubeVideoTapResult(false, "no_accessibility_root")
        if (!isYouTubeRoot(root)) return YouTubeVideoTapResult(false, "not_youtube")
        val screenHeight = resources.displayMetrics.heightPixels
        data class NodeCandidate(
            val node: AccessibilityNodeInfo,
            val metadata: YouTubeVideoCandidate,
            val bounds: Rect
        )
        val candidates = mutableListOf<NodeCandidate>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) {
                val clickable = findClickable(node)
                if (clickable != null && clickable.isVisibleToUser) {
                    val bounds = Rect().also(clickable::getBoundsInScreen)
                    val contextLabel = nodeContextLabel(node) + " " + nodeContextLabel(clickable)
                    val directLabel = listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName)
                        .joinToString(" ").trim()
                    val role = youtubeSemanticRole(directLabel, contextLabel, node)
                    val title = extractVideoSearchQuery(clickable)
                        ?: listOfNotNull(node.text, node.contentDescription)
                            .joinToString(" ").trim()
                    if (!bounds.isEmpty && bounds.top >= (screenHeight * 0.08f).toInt() &&
                        bounds.top < (screenHeight * 0.96f).toInt()
                    ) {
                        val normalizedTitle = title.lowercase(Locale.ROOT)
                            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                            .replace(Regex("\\s+"), " ").trim()
                        val row = bounds.centerY() / (screenHeight * 0.10f).toInt().coerceAtLeast(1)
                        val groupKey = normalizedTitle.takeIf { it.length >= 6 } ?: "row:$row"
                        val id = candidates.size
                        candidates += NodeCandidate(
                            clickable,
                            YouTubeVideoCandidate(
                                id = id,
                                title = title,
                                contextLabel = contextLabel,
                                groupKey = groupKey,
                                top = bounds.top,
                                semanticRole = role
                            ),
                            bounds
                        )
                    }
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        val logical = YouTubeVideoCandidatePolicy.logicalVideos(candidates.map { it.metadata })
        val selected = logical.getOrNull(ordinal - 1)
            ?: return YouTubeVideoTapResult(
                false,
                if (logical.isEmpty()) "no_video_candidates" else "ordinal_out_of_range",
                logical.size
            )
        val nodeCandidate = candidates.firstOrNull { it.metadata.id == selected.id }
            ?: return YouTubeVideoTapResult(false, "stale_candidate", logical.size)
        if (!ForegroundActionPolicy.canExecute(scope, currentForegroundContext()) ||
            !nodeCandidate.node.isVisibleToUser
        ) {
            return YouTubeVideoTapResult(false, "stale_foreground", logical.size)
        }
        if (!YouTubeVideoCandidatePolicy.isSafeVideoOpenRole(selected.semanticRole)) {
            return YouTubeVideoTapResult(false, "wrong_semantic_role", logical.size, selected.title.take(160), nodeCandidate.bounds)
        }
        val clicked = nodeCandidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return YouTubeVideoTapResult(
            clicked,
            if (clicked) "selected" else "click_rejected",
            logical.size,
            selected.title.take(160),
            nodeCandidate.bounds
        )
    }

    fun performYouTubeSemanticAction(
        command: YouTubeSemanticCommand,
        scope: ForegroundActionScope
    ): YouTubeSemanticActionResult {
        if (!ForegroundActionPolicy.canExecute(scope, currentForegroundContext())) {
            return YouTubeSemanticActionResult(false, "stale_foreground")
        }
        val root = rootInActiveWindow ?: return YouTubeSemanticActionResult(false, "no_accessibility_root")
        if (!isYouTubeRoot(root)) return YouTubeSemanticActionResult(false, "not_youtube")
        val nodes = mutableMapOf<String, AccessibilityNodeInfo>()
        val elements = mutableListOf<YouTubeSemanticElement>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) {
                val actionable = when {
                    node.isEditable -> node
                    node.isClickable -> node
                    else -> null
                }
                if (actionable != null) {
                    val bounds = Rect().also(actionable::getBoundsInScreen)
                    val direct = listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName)
                        .joinToString(" ").trim()
                    val context = nodeContextLabel(node)
                    val role = youtubeSemanticRole(direct, context, node)
                    val cardKey = youtubeCardKey(node, bounds)
                    val id = "${bounds.left}:${bounds.top}:${bounds.right}:${bounds.bottom}:${role.name}:${elements.size}"
                    nodes[id] = actionable
                    elements += YouTubeSemanticElement(
                        id, role, (direct.ifBlank { context }).take(240), cardKey, bounds.top,
                        actionable.isClickable || actionable.isEditable,
                        actionable.isSelected || actionable.isChecked ||
                            (role == YouTubeSemanticRole.LIKE_BUTTON && direct.contains("unlike", true)) ||
                            (role == YouTubeSemanticRole.SUBSCRIBE_BUTTON && direct.contains("subscribed", true))
                    )
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        collect(root)
        val resolution = when (command) {
            is YouTubeSemanticCommand.OpenChannel ->
                YouTubeSemanticResolver.resolveChannel(elements, command.name, command.preferProfile)
            YouTubeSemanticCommand.Like -> YouTubeSemanticResolver.resolveControl(elements, YouTubeSemanticRole.LIKE_BUTTON)
            YouTubeSemanticCommand.OpenComments -> YouTubeSemanticResolver.resolveControl(elements, YouTubeSemanticRole.COMMENTS_SECTION)
            YouTubeSemanticCommand.Subscribe -> YouTubeSemanticResolver.resolveControl(elements, YouTubeSemanticRole.SUBSCRIBE_BUTTON)
            YouTubeSemanticCommand.Share -> YouTubeSemanticResolver.resolveControl(elements, YouTubeSemanticRole.SHARE_BUTTON)
            YouTubeSemanticCommand.More -> YouTubeSemanticResolver.resolveControl(elements, YouTubeSemanticRole.MORE_ACTIONS)
            is YouTubeSemanticCommand.TypeText -> YouTubeSemanticResolver.resolveControl(elements, YouTubeSemanticRole.TEXT_INPUT)
            YouTubeSemanticCommand.SendComment -> YouTubeSemanticResolver.resolveControl(elements, YouTubeSemanticRole.SEND_COMMENT)
            YouTubeSemanticCommand.CancelComment -> return YouTubeSemanticActionResult(true, "cancelled")
        }
        if (resolution is YouTubeSemanticResolution.AlreadyActive) {
            return YouTubeSemanticActionResult(true, "already_active", resolution.element.role)
        }
        val selected = (resolution as? YouTubeSemanticResolution.Selected)?.element
            ?: return YouTubeSemanticActionResult(false, if (resolution is YouTubeSemanticResolution.Ambiguous) "ambiguous" else "not_found")
        if (!ForegroundActionPolicy.canExecute(scope, currentForegroundContext())) {
            return YouTubeSemanticActionResult(false, "stale_foreground", selected.role)
        }
        val node = nodes[selected.id] ?: return YouTubeSemanticActionResult(false, "stale_candidate", selected.role)
        val accepted = if (command is YouTubeSemanticCommand.TypeText) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command.payload)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return YouTubeSemanticActionResult(
            accepted, if (accepted) "selected" else "action_rejected", selected.role,
            selected.id.takeIf { selected.role == YouTubeSemanticRole.TEXT_INPUT }
        )
    }

    private fun youtubeSemanticRole(
        directLabel: String,
        contextLabel: String,
        node: AccessibilityNodeInfo
    ): YouTubeSemanticRole {
        val direct = directLabel.lowercase(Locale.ROOT)
        val context = contextLabel.lowercase(Locale.ROOT)
        return when {
            node.isEditable && Regex("comment|reply|add a comment|write").containsMatchIn(context) -> YouTubeSemanticRole.TEXT_INPUT
            Regex("^(?:send|post|comment)$|send comment|post comment").containsMatchIn(direct) -> YouTubeSemanticRole.SEND_COMMENT
            Regex("action menu|more actions|more options|overflow").containsMatchIn(direct) -> YouTubeSemanticRole.MORE_ACTIONS
            Regex("watch later|save|share").containsMatchIn(direct) && direct.contains("share") -> YouTubeSemanticRole.SHARE_BUTTON
            Regex("profile|avatar|channel icon|channel photo").containsMatchIn(direct) -> YouTubeSemanticRole.CHANNEL_PROFILE
            Regex("subscribe|subscribed").containsMatchIn(direct) -> YouTubeSemanticRole.SUBSCRIBE_BUTTON
            Regex("comments?|comment section").containsMatchIn(direct) -> YouTubeSemanticRole.COMMENTS_SECTION
            Regex("\\b(?:like|unlike|thumbs up)\\b").containsMatchIn(direct) && !context.contains("comment") -> YouTubeSemanticRole.LIKE_BUTTON
            Regex("channel name|visit channel|go to channel").containsMatchIn(direct) -> YouTubeSemanticRole.CHANNEL_NAME
            Regex("video[_ ]?title|title").containsMatchIn(direct) -> YouTubeSemanticRole.VIDEO_TITLE
            else -> YouTubeSemanticRole.VIDEO_PLAY_SURFACE
        }
    }

    private fun youtubeCardKey(node: AccessibilityNodeInfo, bounds: Rect): String {
        val title = extractVideoSearchQuery(node)?.lowercase(Locale.ROOT)
            ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")?.trim()
        val row = bounds.centerY() / (resources.displayMetrics.heightPixels * 0.10f).toInt().coerceAtLeast(1)
        return title?.takeIf { it.length >= 6 } ?: "row:$row"
    }

    /** Reuses the semantic YouTube accessibility candidate map; ordinal is one-based. */
    fun tapVisibleYouTubeVideo(ordinal: Int): Boolean {
        val scope = ForegroundActionPolicy.scope(currentForegroundContext()) ?: return false
        return resolveAndTapYouTubeVideo(ordinal, scope).accepted
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

    /** Accessibility is the authoritative target map; Screen Vision never guesses raw coordinates. */
    fun visibleElements(limit: Int = 120): List<VisibleScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<VisibleScreenElement>()
        fun collect(node: AccessibilityNodeInfo) {
            if (result.size >= limit) return
            if (node.isVisibleToUser) {
                val rawText = node.text?.toString().orEmpty().trim()
                val description = node.contentDescription?.toString().orEmpty().trim()
                val hint = node.hintText?.toString().orEmpty().trim()
                val label = listOf(rawText, description, hint).filter(String::isNotBlank)
                    .joinToString(" ").trim().replace(Regex("\\s+"), " ")
                if (label.isNotBlank()) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.isEmpty) result += VisibleScreenElement(
                        label.take(240), bounds, findClickable(node) != null,
                        node.className?.toString().orEmpty(), rawText.take(240),
                        description.take(240), hint.take(240), node.isLongClickable,
                        node.isScrollable, node.isEditable, node.isEnabled, node.isSelected,
                        node.isChecked, node.isFocused, node.viewIdResourceName
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

    fun detectContentType(): com.myra.assistant.screen.ScreenContentType {
        val root = rootInActiveWindow ?: return com.myra.assistant.screen.ScreenContentType.OTHER
        val packageName = root.packageName?.toString().orEmpty().lowercase(Locale.ROOT)
        if (packageName == YOUTUBE_PACKAGE || packageName.contains("youtube")) {
            return com.myra.assistant.screen.ScreenContentType.VIDEO_PLATFORM
        }
        if (packageName.contains("instagram") || packageName.contains("facebook") ||
            packageName.contains("twitter") || packageName.contains("tiktok") || packageName.contains("reddit")
        ) return com.myra.assistant.screen.ScreenContentType.SOCIAL_FEED

        val elements = visibleElements(160)
        val text = elements.joinToString(" ") { it.label }.lowercase(Locale.ROOT)
        val articleSignals = listOf("article", "published", "updated", "minute read", "read time", "by ")
            .count(text::contains)
        val meaningfulLines = articleBodyLines(elements)
        val articleChars = meaningfulLines.sumOf(String::length)
        val browserLike = packageName.contains("chrome") || packageName.contains("browser") || packageName.contains("firefox")
        return when {
            articleSignals >= 1 && articleChars >= 300 ->
                com.myra.assistant.screen.ScreenContentType.ARTICLE
            browserLike && articleChars >= 500 &&
                (meaningfulLines.size >= 2 || (meaningfulLines.maxOfOrNull(String::length) ?: 0) >= 400) ->
                com.myra.assistant.screen.ScreenContentType.ARTICLE
            browserLike ->
                com.myra.assistant.screen.ScreenContentType.WEB_PAGE
            else -> com.myra.assistant.screen.ScreenContentType.OTHER
        }
    }

    fun visibleArticleText(): List<String> = articleBodyLines(visibleElements(180))

    private fun articleBodyLines(elements: List<VisibleScreenElement>): List<String> {
        val screenHeight = resources.displayMetrics.heightPixels
        return elements.asSequence()
            .filter { it.bounds.top >= 0 && it.bounds.bottom <= screenHeight && it.bounds.height() > 0 }
            .map { it.label.trim().replace(Regex("\\s+"), " ") }
            .filter { line ->
                line.length >= 24 && !Regex(
                    "^(?:home|menu|search|share|sign in|log in|subscribe|comments?|related|recommended|advertisement|cookie|privacy|next|previous)$",
                    RegexOption.IGNORE_CASE
                ).matches(line)
            }
            .distinct()
            .toList()
    }

    fun currentArticleScrollContainerId(): String? {
        if (detectContentType() != com.myra.assistant.screen.ScreenContentType.ARTICLE) return null
        val root = rootInActiveWindow ?: return null
        return articleScrollContainers(root).firstOrNull()?.identity
    }

    /** Scrolls only the exact container owned by the active article session. */
    fun scrollArticleVerified(
        expectedContainerId: String,
        expectedPackage: String,
        expectedScreenSessionId: String,
        onResult: (Boolean) -> Unit
    ): Boolean {
        if (!ScreenCaptureService.session.isCurrent(expectedScreenSessionId) ||
            currentPackageName() != expectedPackage ||
            detectContentType() != com.myra.assistant.screen.ScreenContentType.ARTICLE
        ) return false
        val before = visibleScreenSignature()
        val root = rootInActiveWindow ?: return false
        val container = articleScrollContainers(root).firstOrNull { it.identity == expectedContainerId }
            ?: return false
        val accepted = container.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        if (!accepted) return false
        Handler(Looper.getMainLooper()).postDelayed({
            val changed = before.isNotBlank() && visibleScreenSignature() != before
            if (changed) refreshScreenContext(force = true)
            onResult(changed)
        }, 500L)
        return true
    }

    fun scrollArticleToBeginning(
        expectedContainerId: String,
        expectedPackage: String,
        expectedScreenSessionId: String,
        onResult: (Boolean) -> Unit
    ): Boolean {
        if (!ScreenCaptureService.session.isCurrent(expectedScreenSessionId) ||
            currentPackageName() != expectedPackage ||
            detectContentType() != com.myra.assistant.screen.ScreenContentType.ARTICLE
        ) return false
        fun step(remaining: Int, moved: Boolean) {
            if (remaining <= 0) { onResult(moved); return }
            val root = rootInActiveWindow ?: run { onResult(moved); return }
            val before = visibleScreenSignature()
            val container = articleScrollContainers(root).firstOrNull { it.identity == expectedContainerId }
                ?: run { onResult(moved); return }
            val accepted = container.node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            if (!accepted) { onResult(moved); return }
            Handler(Looper.getMainLooper()).postDelayed({
                val changed = before.isNotBlank() && visibleScreenSignature() != before
                if (!changed) onResult(moved) else step(remaining - 1, true)
            }, 300L)
        }
        step(12, false)
        return true
    }

    private data class ArticleScrollContainer(
        val area: Long,
        val identity: String,
        val node: AccessibilityNodeInfo
    )

    private fun articleScrollContainers(root: AccessibilityNodeInfo): List<ArticleScrollContainer> {
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName.isBlank() || packageName == YOUTUBE_PACKAGE) return emptyList()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val result = mutableListOf<ArticleScrollContainer>()
        fun collect(node: AccessibilityNodeInfo, path: String) {
            if (node.isVisibleToUser && node.isScrollable) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.width() >= screenWidth * 0.55 && bounds.height() >= screenHeight * 0.35) {
                    val raw = listOf(
                        packageName, node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
                        path
                    ).joinToString("|")
                    val identity = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(raw.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                    result += ArticleScrollContainer(bounds.width().toLong() * bounds.height(), identity, node)
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { collect(it, "$path/$index") }
            }
        }
        collect(root, "root")
        return result.sortedByDescending { it.area }
    }

    fun currentPackageName(): String? = rootInActiveWindow?.packageName?.toString()

    @Synchronized
    fun currentForegroundContext(): ForegroundAppContext? {
        val root = rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return null
        val windowId = root.windowId
        if (foregroundPackage != packageName || foregroundWindowId != windowId) {
            foregroundPackage = packageName
            foregroundWindowId = windowId
            foregroundGeneration += 1L
        }
        val appName = runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
        return ForegroundAppContext(
            packageName = packageName,
            appName = appName,
            windowId = windowId,
            generation = foregroundGeneration,
            observedAt = android.os.SystemClock.elapsedRealtime()
        )
    }

    /**
     * Manual scroll is scoped to the live foreground window. It never launches an app,
     * reuses an article binding, or falls back to gesture coordinates.
     */
    fun scrollCurrentForegroundVerified(
        scope: ForegroundActionScope,
        down: Boolean,
        onResult: (Boolean) -> Unit
    ): Boolean {
        if (!ForegroundActionPolicy.canExecute(scope, currentForegroundContext())) return false
        val root = rootInActiveWindow ?: return false
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val candidates = mutableListOf<Pair<Long, AccessibilityNodeInfo>>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.isScrollable) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.width() >= screenWidth * 0.40 && bounds.height() >= screenHeight * 0.25) {
                    candidates += bounds.width().toLong() * bounds.height() to node
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        val before = visibleScreenSignature()
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val accepted = candidates.sortedByDescending { it.first }
            .firstOrNull { (_, node) ->
                ForegroundActionPolicy.canExecute(scope, currentForegroundContext()) &&
                    node.isVisibleToUser && node.performAction(action)
            } != null
        if (!accepted) return false
        Handler(Looper.getMainLooper()).postDelayed({
            val stillOwned = ForegroundActionPolicy.canExecute(scope, currentForegroundContext())
            val changed = stillOwned && before.isNotBlank() && visibleScreenSignature() != before
            if (changed) refreshScreenContext()
            onResult(changed)
        }, 420L)
        return true
    }

    fun lastSnapshotAt(): Long = accessibilitySnapshotAt

    fun refreshScreenContext(
        observedAt: Long = android.os.SystemClock.elapsedRealtime(),
        force: Boolean = false
    ) {
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString()
        val appName = packageName?.let { value ->
            runCatching {
                val info = packageManager.getApplicationInfo(value, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrNull()
        }
        val elements = visibleElements(120)
        val foreground = currentForegroundContext()
        if (foreground != null) {
            val semantic = elements.mapIndexed { index, element ->
                SemanticElement(
                    id = "${foreground.windowId}:${foreground.generation}:$index",
                    role = SemanticRoleClassifier.classify(element.label, element.className, element.clickable, element.scrollable),
                    label = element.label.take(240), left = element.bounds.left, top = element.bounds.top,
                    right = element.bounds.right, bottom = element.bounds.bottom,
                    actionable = element.clickable, sourceNodeId = element.sourceNodeId,
                    packageName = foreground.packageName, windowId = foreground.windowId,
                    screenGeneration = foreground.generation, text = element.text,
                    contentDescription = element.contentDescription, hint = element.hint,
                    className = element.className, clickable = element.clickable,
                    longClickable = element.longClickable, scrollable = element.scrollable,
                    editable = element.editable, enabled = element.enabled,
                    selected = element.selected, checked = element.checked, focused = element.focused,
                    horizontalPosition = when {
                        element.bounds.centerX() < resources.displayMetrics.widthPixels / 3 -> com.myra.assistant.agent.RelativeHorizontalPosition.LEFT
                        element.bounds.centerX() > resources.displayMetrics.widthPixels * 2 / 3 -> com.myra.assistant.agent.RelativeHorizontalPosition.RIGHT
                        else -> com.myra.assistant.agent.RelativeHorizontalPosition.CENTER
                    },
                    verticalPosition = when {
                        element.bounds.centerY() < resources.displayMetrics.heightPixels / 3 -> com.myra.assistant.agent.RelativeVerticalPosition.TOP
                        element.bounds.centerY() > resources.displayMetrics.heightPixels * 2 / 3 -> com.myra.assistant.agent.RelativeVerticalPosition.BOTTOM
                        else -> com.myra.assistant.agent.RelativeVerticalPosition.MIDDLE
                    },
                    possibleActions = buildSet {
                        if (element.clickable) add(com.myra.assistant.agent.ToolCapability.ACCESSIBILITY_CLICK)
                        if (element.longClickable) add(com.myra.assistant.agent.ToolCapability.LONG_PRESS)
                        if (element.scrollable) add(com.myra.assistant.agent.ToolCapability.ACCESSIBILITY_SCROLL)
                        if (element.editable) add(com.myra.assistant.agent.ToolCapability.ACCESSIBILITY_TYPE)
                    }
                )
            }
            val observation = CurrentActivityContext(
                packageName = foreground.packageName, appLabel = foreground.appName,
                screenType = detectContentType().name, windowId = foreground.windowId,
                generation = foreground.generation, visibleElements = semantic,
                confidence = if (semantic.isEmpty()) 0.25 else 0.9, timestamp = observedAt
            )
            if (!activityObservationCoalescer.shouldPublish(observation, force)) {
                com.myra.assistant.diagnostics.VoicePipelineLogger.debug(
                    "SCREEN_CONTEXT_COALESCED package=${observation.packageName} windowId=${observation.windowId} semanticElements=${semantic.size}"
                )
                return
            }
            val updated = ActivityContextStore.update(observation)
            UnifiedLyraAgentRuntime.agent.invalidateForContext(updated)
            com.myra.assistant.agent.WorkingTaskRuntime.store.invalidateIfExternalAppChanged(
                updated.packageName, updated.generation
            )
            com.myra.assistant.diagnostics.VoicePipelineLogger.debug(
                "agent_observation package=${updated.packageName} windowGeneration=${updated.generation} semanticElements=${semantic.size} screenshotUsed=false"
            )
        }
        com.myra.assistant.screen.ScreenContextStore.onAccessibility(
            ScreenCaptureService.session.sessionId, packageName, appName, elements, observedAt,
            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels
        )
        com.myra.assistant.diagnostics.VoicePipelineLogger.debug(
            "SCREEN_CONTEXT_UPDATED screen_session_id=${ScreenCaptureService.session.sessionId} " +
                "timestamp=$observedAt package=$packageName visibleElements=${elements.size}"
        )
    }

    fun tapVisibleTarget(
        targetText: String?,
        position: String?,
        ordinal: Int?
    ): Boolean = resolveAndTapVisibleTarget(targetText, position, ordinal).accepted

    fun resolveAndTapVisibleTarget(
        targetText: String?,
        position: String?,
        ordinal: Int?,
        expectedScope: ForegroundActionScope? = null,
        authorizeTap: ((ScreenTargetCandidate, Double) -> Boolean)? = null
    ): VisibleTargetTapResult {
        val initialContext = currentForegroundContext()
            ?: return VisibleTargetTapResult(false, resolution = "no_accessibility_root")
        if (expectedScope != null && !ForegroundActionPolicy.canExecute(expectedScope, initialContext)) {
            return VisibleTargetTapResult(false, resolution = "stale_foreground")
        }
        val root = rootInActiveWindow
            ?: return VisibleTargetTapResult(false, resolution = "no_accessibility_root")
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        data class Candidate(val id: Int, val node: AccessibilityNodeInfo, val label: String, val bounds: Rect, val role: String)
        val candidates = mutableListOf<Candidate>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser) {
                val clickable = findClickable(node)
                val label = listOfNotNull(node.text, node.contentDescription)
                    .joinToString(" ").trim().replace(Regex("\\s+"), " ")
                if (clickable != null && label.isNotBlank()) {
                    val bounds = Rect().also(clickable::getBoundsInScreen)
                    if (!bounds.isEmpty) {
                        val className = node.className?.toString().orEmpty()
                        val contextLabel = nodeContextLabel(node) + " " + nodeContextLabel(clickable)
                        val youtubeVideo = root.packageName?.toString() == YOUTUBE_PACKAGE &&
                            looksLikeVideoCard(contextLabel, afterPlayer = false) &&
                            !AD_SIGNAL.containsMatchIn(contextLabel) &&
                            !NON_VIDEO_CONTROLS.containsMatchIn(contextLabel)
                        val youtubeRole = if (root.packageName?.toString() == YOUTUBE_PACKAGE) {
                            youtubeSemanticRole(label, contextLabel, node)
                        } else null
                        val role = when {
                            youtubeRole == YouTubeSemanticRole.LIKE_BUTTON -> "like_control"
                            youtubeRole == YouTubeSemanticRole.SUBSCRIBE_BUTTON -> "subscribe_control"
                            youtubeRole == YouTubeSemanticRole.COMMENTS_SECTION -> "comments_control"
                            youtubeRole == YouTubeSemanticRole.CHANNEL_PROFILE -> "channel_profile"
                            youtubeVideo -> "video"
                            className.contains("button", true) -> "button"
                            else -> "interactive"
                        }
                        val semanticLabel = if (youtubeVideo) {
                            extractVideoSearchQuery(clickable) ?: label
                        } else label
                        candidates += Candidate(candidates.size, clickable, semanticLabel, bounds, role)
                    }
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        val unique = candidates
            .groupBy { "${it.role.lowercase(Locale.ROOT)}:${it.bounds}" }
            .map { (_, group) -> group.maxByOrNull { it.label.length } ?: group.first() }
            .mapIndexed { index, item -> item.copy(id = index) }
        val resolution = ScreenTargetResolver.resolve(
            unique.map {
                ScreenTargetCandidate(
                    it.id, it.label, it.role, it.bounds.left, it.bounds.top,
                    it.bounds.right, it.bounds.bottom
                )
            },
            targetText, position, ordinal, screenWidth, screenHeight
        )
        val selected = (resolution as? ScreenTargetResolution.Selected)
            ?: return VisibleTargetTapResult(
                false,
                resolution = if (resolution is ScreenTargetResolution.Ambiguous) "ambiguous" else "not_found"
            )
        val node = unique.firstOrNull { it.id == selected.candidate.id }?.node
            ?: return VisibleTargetTapResult(false, selected.candidate, selected.confidence, "stale_candidate")
        if (expectedScope != null &&
            !ForegroundActionPolicy.canExecute(expectedScope, currentForegroundContext())
        ) {
            return VisibleTargetTapResult(false, selected.candidate, selected.confidence, "stale_foreground")
        }
        if (!node.isVisibleToUser) {
            return VisibleTargetTapResult(false, selected.candidate, selected.confidence, "stale_candidate")
        }
        if (authorizeTap?.invoke(selected.candidate, selected.confidence) == false) {
            return VisibleTargetTapResult(false, selected.candidate, selected.confidence, "authorization_rejected")
        }
        if (expectedScope != null &&
            !ForegroundActionPolicy.canExecute(expectedScope, currentForegroundContext())
        ) {
            return VisibleTargetTapResult(false, selected.candidate, selected.confidence, "stale_foreground")
        }
        return VisibleTargetTapResult(
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            selected.candidate,
            selected.confidence,
            "selected"
        )
    }

    /** Executes only a semantic target bound to the current foreground/window/scene. */
    fun tapResolvedSemanticTarget(target: SemanticElement): GenericSemanticTapResult {
        val context = ActivityContextStore.snapshot()
            ?: return GenericSemanticTapResult(false, "NONE", "no_scene", target.id)
        val foreground = currentForegroundContext()
            ?: return GenericSemanticTapResult(false, "NONE", "no_accessibility_root", target.id)
        val currentElement = context.visibleElements.firstOrNull { it.id == target.id }
        if (target.packageName != foreground.packageName || target.windowId != foreground.windowId ||
            target.screenGeneration != context.generation || context.packageName != foreground.packageName ||
            context.windowId != foreground.windowId || currentElement == null ||
            currentElement.label != target.label || currentElement.left != target.left || currentElement.top != target.top ||
            currentElement.right != target.right || currentElement.bottom != target.bottom
        ) return GenericSemanticTapResult(false, "NONE", "stale_target", target.id)
        val root = rootInActiveWindow
            ?: return GenericSemanticTapResult(false, "NONE", "no_accessibility_root", target.id)
        val expectedBounds = Rect(target.left, target.top, target.right, target.bottom)
        var exact: AccessibilityNodeInfo? = null
        fun collect(node: AccessibilityNodeInfo) {
            if (exact != null || !node.isVisibleToUser) return
            val bounds = Rect().also(node::getBoundsInScreen)
            val label = listOfNotNull(node.text, node.contentDescription, node.hintText)
                .joinToString(" ").trim().replace(Regex("\\s+"), " ")
            val idMatches = target.sourceNodeId != null && node.viewIdResourceName == target.sourceNodeId
            if (bounds == expectedBounds && (idMatches || label.equals(target.label, true))) exact = node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        exact?.let { node ->
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return GenericSemanticTapResult(true, "ACCESSIBILITY_CLICK", "exact_node", target.id)
            }
            val ancestor = findClickable(node)
            if (ancestor != null && ancestor !== node && ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return GenericSemanticTapResult(true, "ACCESSIBILITY_ANCESTOR_CLICK", "clickable_ancestor", target.id)
            }
        }
        val latest = ActivityContextStore.snapshot()
        val latestForeground = currentForegroundContext()
        if (latest?.generation != target.screenGeneration || latest.packageName != target.packageName ||
            latest.windowId != target.windowId || latestForeground?.packageName != target.packageName ||
            latestForeground?.windowId != target.windowId || expectedBounds.isEmpty
        ) return GenericSemanticTapResult(false, "NONE", "stale_bounds", target.id)
        val path = Path().apply { moveTo(expectedBounds.centerX().toFloat(), expectedBounds.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L)).build()
        val accepted = dispatchGesture(gesture, null, null)
        return GenericSemanticTapResult(accepted, "GESTURE_LAST_RESORT", if (accepted) "fresh_bounds" else "gesture_rejected", target.id)
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
