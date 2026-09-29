package com.myra.assistant.ui.workspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.lang.ref.WeakReference

/**
 * Process-lifetime guard for a protected GitHub coding task.
 *
 * The actual task state stays in WorkspaceGitHubSelfEditFlow + its durable checkpoint. This
 * foreground service keeps the process eligible while the user opens another app, provides the
 * required ongoing notification, and survives task removal. If Android still kills the process,
 * START_STICKY restarts only the notification shell; reopening LYRA resumes the durable checkpoint.
 */
class WorkspaceGitHubBackgroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "lyra_github_coding"
        private const val NOTIFICATION_ID = 62641
        private const val FINAL_NOTIFICATION_ID = 62642
        private const val ACTION_START = "com.myra.assistant.workspace.GITHUB_BG_START"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_DETAIL = "detail"

        @Volatile private var instance = WeakReference<WorkspaceGitHubBackgroundService>(null)

        fun start(context: Context, label: String, detail: String? = null) {
            val intent = Intent(context, WorkspaceGitHubBackgroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LABEL, label.take(90))
                .putExtra(EXTRA_DETAIL, detail?.take(160))
            context.startForegroundService(intent)
        }

        fun update(context: Context, label: String, detail: String? = null) {
            val live = instance.get()
            if (live != null) {
                live.showWorking(label, detail)
            } else {
                runCatching { start(context, label, detail) }
            }
        }

        fun complete(context: Context, detail: String) {
            val live = instance.get()
            if (live != null) {
                live.finishWithNotification("LYRA GitHub task complete", detail, success = true)
            } else {
                postFinal(context, "LYRA GitHub task complete", detail)
            }
        }

        fun fail(context: Context, detail: String) {
            val prefs = context.getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
            val resumable = WorkspaceGitHubBackgroundPolicy.hasCheckpoint(
                prefs.getString(WorkspaceGitHubBackgroundPolicy.CHECKPOINT_SHA_KEY, null)
            )
            val title = if (resumable) "LYRA GitHub task paused" else "LYRA GitHub task stopped"
            val body = if (resumable) {
                "Open LYRA to resume the same checkpoint"
            } else {
                detail.take(180)
            }
            val live = instance.get()
            if (live != null) live.finishWithNotification(title, body, success = false)
            else postFinal(context, title, body)
        }

        fun stop(context: Context) {
            instance.get()?.stopNow()
            context.stopService(Intent(context, WorkspaceGitHubBackgroundService::class.java))
        }

        private fun postFinal(context: Context, title: String, detail: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            ensureChannel(manager)
            manager.notify(
                FINAL_NOTIFICATION_ID,
                buildNotification(context, title, detail, ongoing = false),
            )
        }

        private fun ensureChannel(manager: NotificationManager) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "LYRA GitHub coding",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Background progress for protected LYRA GitHub coding tasks"
                    setShowBadge(false)
                }
            )
        }

        private fun buildNotification(
            context: Context,
            title: String,
            detail: String,
            ongoing: Boolean,
        ): Notification {
            val open = PendingIntent.getActivity(
                context,
                626,
                Intent(context, WorkspaceActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(
                    if (ongoing) android.R.drawable.stat_sys_upload
                    else android.R.drawable.stat_sys_upload_done
                )
                .setContentTitle(title.take(90))
                .setContentText(detail.take(180))
                .setStyle(Notification.BigTextStyle().bigText(detail.take(600)))
                .setContentIntent(open)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(ongoing)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        ensureChannel(getSystemService(NotificationManager::class.java))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
        if (intent?.action == ACTION_START) {
            val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                .ifBlank { "LYRA GitHub coding" }
            val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
                .ifBlank { "Protected task running in background" }
            showWorking(label, detail)
            return START_STICKY
        }

        val resumable = WorkspaceGitHubBackgroundPolicy.hasCheckpoint(
            prefs.getString(WorkspaceGitHubBackgroundPolicy.CHECKPOINT_SHA_KEY, null)
        )
        if (resumable) {
            showWorking(
                "LYRA GitHub task paused safely",
                WorkspaceGitHubBackgroundPolicy.restartDetail(true),
            )
            return START_STICKY
        }
        stopNow()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val prefs = getSharedPreferences("workspace_ui", Context.MODE_PRIVATE)
        val resumable = WorkspaceGitHubBackgroundPolicy.hasCheckpoint(
            prefs.getString(WorkspaceGitHubBackgroundPolicy.CHECKPOINT_SHA_KEY, null)
        )
        if (resumable) {
            showWorking(
                "LYRA GitHub coding continues",
                "You can reopen LYRA anytime; the same checkpoint is preserved",
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instance.get() === this) instance.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showWorking(label: String, detail: String?) {
        val body = detail?.takeIf(String::isNotBlank) ?: "Protected GitHub task running"
        startForeground(
            NOTIFICATION_ID,
            buildNotification(this, label, body, ongoing = true),
        )
    }

    private fun finishWithNotification(title: String, detail: String, success: Boolean) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            FINAL_NOTIFICATION_ID,
            buildNotification(
                this,
                title,
                detail,
                ongoing = false,
            ),
        )
        stopSelf()
    }

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
