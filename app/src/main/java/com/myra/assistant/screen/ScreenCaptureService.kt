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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArraySet

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
        val maxWidth = 1080
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
            val now = android.os.SystemClock.elapsedRealtime()
            val interval = ScreenVisionPreferences(this).analysisIntervalMs
            if (now - lastProcessedAt < interval) return
            val plane = image.planes.firstOrNull() ?: return
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val paddedWidth = width + rowPadding / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            try {
                padded.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                try {
                    if (!changeDetector.changed(cropped) && latestFrame != null) return
                    val output = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 82, output)
                    latestFrame = output.toByteArray()
                    latestFrameAt = System.currentTimeMillis()
                    lastProcessedAt = now
                    listeners.forEach { it(currentState, latestFrame) }
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
        latestFrame = null
        latestFrameAt = 0L
        changeDetector.reset()
        updateState(finalState)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null || display != null || reader != null) stopCapture(ScreenShareState.STOPPED)
        workerThread?.quitSafely()
        workerThread = null; worker = null
        super.onDestroy()
    }

    private fun updateState(state: ScreenShareState) {
        if (state != stateMachine.state && !stateMachine.transition(state)) {
            Log.w(TAG, "screen_share_invalid_transition from=${stateMachine.state} to=$state")
            return
        }
        currentState = state
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
    }
}
