package com.myra.assistant.screen

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.service.AccessibilityHelperService
import com.myra.assistant.diagnostics.VoicePipelineLogger
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap

/** Real MediaProjection capture. Frames remain in memory and are never written to disk. */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private val changeDetector = ScreenFrameChangeDetector()
    private val stateMachine = ScreenShareStateMachine(currentState)
    private var lastProcessedAt = 0L
    private var lastLocalCacheAt = 0L
    private val freshRequests = ConcurrentHashMap<String, (FreshFrameResult) -> Unit>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        workerThread = HandlerThread("LyraScreenCapture").also { it.start() }
        worker = Handler(workerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_PAUSE -> updateState(ScreenShareState.PAUSED)
            ACTION_RESUME -> {
                updateState(ScreenShareState.RESUMING)
                changeDetector.reset()
                updateState(ScreenShareState.ACTIVE)
            }
            ACTION_STOP -> stopCapture(ScreenShareState.STOPPED)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (projection != null) return
        val createdSession = session.start()
        Log.d(TAG, "screen_session_created screen_session_id=$createdSession")
        updateState(ScreenShareState.REQUESTING_PERMISSION)
        startForeground(NOTIFICATION_ID, notification("Screen sharing is starting…"))
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            updateState(ScreenShareState.ERROR)
            stopSelf()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)?.also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture(ScreenShareState.STOPPED) }
            }, worker)
        }
        val metrics = resources.displayMetrics
        val maxWidth = 960
        val scale = minOf(1f, maxWidth.toFloat() / metrics.widthPixels)
        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2)
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { imageReader ->
            imageReader.setOnImageAvailableListener({ source -> consumeLatest(source, width, height) }, worker)
        }
        display = projection?.createVirtualDisplay(
            "LYRA Screen Vision", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, worker
        )
        if (display == null) {
            stopCapture(ScreenShareState.ERROR)
        } else {
            updateState(ScreenShareState.ACTIVE)
            notifyManager().notify(NOTIFICATION_ID, notification("LYRA Screen Vision active"))
        }
    }

    private fun consumeLatest(source: ImageReader, width: Int, height: Int) {
        val image = source.acquireLatestImage() ?: return
        try {
            if (currentState != ScreenShareState.ACTIVE) return
            val captureStartedAt = System.currentTimeMillis()
            val now = android.os.SystemClock.elapsedRealtime()
            val explicit = freshRequests.isNotEmpty()
            val interval = ScreenVisionPreferences(this).analysisIntervalMs
            val localCacheDue = now - lastLocalCacheAt >= LOCAL_FRAME_CACHE_INTERVAL_MS
            val passiveDue = now - lastProcessedAt >= interval
            if (!explicit && !localCacheDue && !passiveDue) return
            val plane = image.planes.firstOrNull() ?: return
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val paddedWidth = width + rowPadding / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            try {
                padded.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                try {
                    // Keep local sight fresh independently from the slower, configurable
                    // Gemini upload interval. This cache is memory-only and is not routed
                    // to Live unless an explicit query uses it.
                    val passiveChanged = passiveDue && (latestFrame == null || changeDetector.changed(cropped))
                    if (!explicit && !localCacheDue && !passiveChanged) return
                    val output = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 76, output)
                    val encodedAt = System.currentTimeMillis()
                    val sourceName = when {
                        explicit -> "explicit_query"
                        passiveChanged -> "passive"
                        else -> "local_cache"
                    }
                    val record = session.publish(output.toByteArray(), captureStartedAt, encodedAt, sourceName) ?: return
                    latestFrame = record.bytes
                    latestFrameAt = record.capturedAt
                    lastLocalCacheAt = now
                    if (passiveChanged) lastProcessedAt = now
                    if (explicit || passiveChanged) {
                        val frameLog = "frame_captured screen_session_id=${record.sessionId} frame_id=${record.frameId} frame_hash=${record.hash} source=${record.source} frameCapturedAt=${record.capturedAt} frameEncodedAt=${record.encodedAt} captureToEncodeMs=${record.encodedAt - record.capturedAt} bytes=${record.bytes.size}"
                        Log.d(TAG, frameLog); VoicePipelineLogger.debug(frameLog)
                    }
                    // Only meaningful passive observations go to the periodic Gemini
                    // video transport. Local cache frames never become continuous uploads.
                    if (passiveChanged && !explicit) listeners.forEach { it(currentState, latestFrame) }
                    if (explicit) {
                        freshRequests.keys.toList().forEach { queryId ->
                            val callback = freshRequests.remove(queryId) ?: return@forEach
                            session.complete(queryId, record)?.let(callback)
                        }
                    }
                } finally { cropped.recycle() }
            } finally { padded.recycle() }
        } catch (error: Exception) {
            Log.w(TAG, "screen_frame_failed", error)
        } finally { image.close() }
    }

    private fun stopCapture(finalState: ScreenShareState) {
        if (currentState == ScreenShareState.STOPPING || currentState == ScreenShareState.STOPPED) return
        updateState(ScreenShareState.STOPPING)
        reader?.setOnImageAvailableListener(null, null)
        display?.release(); display = null
        reader?.close(); reader = null
        val activeProjection = projection; projection = null
        runCatching { activeProjection?.stop() }
        val cancelled = session.invalidate(finalState)
        cancelled.forEach { query -> freshRequests.remove(query.queryId)?.invoke(FreshFrameResult.Unavailable(query, "screen_stopped")) }
        freshRequests.clear()
        latestFrame = null
        latestFrameAt = 0L
        lastLocalCacheAt = 0L
        changeDetector.reset()
        Log.d(TAG, "screen_stop_invalidated_frames cancelledQueries=${cancelled.size}")
        updateState(finalState)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null || display != null || reader != null) stopCapture(ScreenShareState.STOPPED)
        workerThread?.quitSafely()
        workerThread = null; worker = null
        if (serviceInstance === this) serviceInstance = null
        super.onDestroy()
    }

    private fun updateState(state: ScreenShareState) {
        if (state != stateMachine.state && !stateMachine.transition(state)) {
            Log.w(TAG, "screen_share_invalid_transition from=${stateMachine.state} to=$state")
            return
        }
        currentState = state
        session.setState(state)
        AccessibilityHelperService.instance?.updateScreenVisionOverlay(state)
        listeners.forEach { it(state, latestFrame) }
        Log.d(TAG, "screen_share_state=$state frameAvailable=${latestFrame != null}")
    }

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyra_sparkle)
            .setContentTitle("LYRA is sharing your screen")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) notifyManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }
    private fun notifyManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val ACTION_START = "com.myra.screen.START"
        const val ACTION_PAUSE = "com.myra.screen.PAUSE"
        const val ACTION_RESUME = "com.myra.screen.RESUME"
        const val ACTION_STOP = "com.myra.screen.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "lyra_screen_share"
        private const val NOTIFICATION_ID = 1201
        private const val TAG = "LyraScreenVision"
        @Volatile var currentState: ScreenShareState = ScreenShareState.IDLE
            private set
        @Volatile var latestFrame: ByteArray? = null
            private set
        @Volatile var latestFrameAt: Long = 0L
            private set
        val session = ScreenVisionSession()
        @Volatile private var serviceInstance: ScreenCaptureService? = null
        val listeners = CopyOnWriteArraySet<(ScreenShareState, ByteArray?) -> Unit>()
        fun markPermissionRequesting() {
            currentState = ScreenShareState.REQUESTING_PERMISSION
            listeners.forEach { it(currentState, latestFrame) }
        }
        fun markPermissionDenied() {
            currentState = ScreenShareState.ERROR
            latestFrame = null
            latestFrameAt = 0L
            listeners.forEach { it(currentState, null) }
        }
        fun hasFreshFrame(maxAgeMs: Long = 15_000L): Boolean =
            currentState == ScreenShareState.ACTIVE && latestFrame != null &&
                System.currentTimeMillis() - latestFrameAt <= maxAgeMs

        fun requestFreshFrame(userTurnId: Long, callback: (FreshFrameResult) -> Unit): ScreenQuery? {
            val query = session.createQuery(userTurnId, System.currentTimeMillis()) ?: return null
            val service = serviceInstance
            if (service == null) {
                callback(FreshFrameResult.Unavailable(query, "capture_service_unavailable"))
                return query
            }
            val requestLog = "frame_capture_requested screen_session_id=${query.sessionId} screen_query_id=${query.queryId} userTurnId=$userTurnId freshCaptureRequestedAt=${query.requestedAt}"
            Log.d(TAG, requestLog); VoicePipelineLogger.debug(requestLog)
            // A video produces ImageReader frames continuously. Use the newest local
            // frame immediately when it is already current instead of waiting for the
            // periodic 3/5/10 second observation interval or a compositor nudge.
            val immediate = session.completeWithLatest(
                query.queryId, System.currentTimeMillis(), IMMEDIATE_QUERY_FRAME_MAX_AGE_MS
            )
            if (immediate is FreshFrameResult.Ready) {
                VoicePipelineLogger.debug(
                    "screen_query_cache_hit screen_query_id=${query.queryId} frame_id=${immediate.frame.frameId} " +
                        "frame_age_ms=${System.currentTimeMillis() - immediate.frame.capturedAt}"
                )
                callback(immediate)
                return query
            }
            service.freshRequests[query.queryId] = callback
            service.worker?.post {
                // A static screen may not produce another buffer immediately. Reattaching
                // the existing projection surface requests one fresh compositor frame;
                // it does not restart MediaProjection or create a second display.
                runCatching {
                    service.display?.setSurface(null)
                    service.display?.setSurface(service.reader?.surface)
                }.onFailure { VoicePipelineLogger.debug("frame_capture_nudge_failed screen_query_id=${query.queryId} reason=${it.javaClass.simpleName}") }
            }
            service.worker?.postDelayed({
                val pending = service.freshRequests.remove(query.queryId) ?: return@postDelayed
                val fallback = session.completeWithLatest(
                    query.queryId, System.currentTimeMillis(), QUERY_FALLBACK_FRAME_MAX_AGE_MS
                )
                if (fallback is FreshFrameResult.Ready) {
                    VoicePipelineLogger.debug(
                        "screen_query_cache_fallback screen_query_id=${query.queryId} frame_id=${fallback.frame.frameId} " +
                            "frame_age_ms=${System.currentTimeMillis() - fallback.frame.capturedAt}"
                    )
                    pending(fallback)
                } else session.cancel(query.queryId, "fresh_capture_timeout")?.let(pending)
            }, FRESH_CAPTURE_TIMEOUT_MS)
            return query
        }
        fun currentFrame(): ScreenFrame? = session.latestFrame
        private const val LOCAL_FRAME_CACHE_INTERVAL_MS = 400L
        private const val IMMEDIATE_QUERY_FRAME_MAX_AGE_MS = 500L
        private const val QUERY_FALLBACK_FRAME_MAX_AGE_MS = 1_250L
        private const val FRESH_CAPTURE_TIMEOUT_MS = 650L
    }

    init { serviceInstance = this }
}
