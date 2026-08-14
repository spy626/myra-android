package com.myra.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Reads WhatsApp notifications and preserves Android's official inline-reply action. */
class WhatsAppNotificationService : NotificationListenerService() {
    private val seen = ConcurrentHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val message = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (sender.isBlank() || message.isBlank() || isSummary(message)) return

        val fingerprint = "${sbn.key}|$sender|$message"
        val now = System.currentTimeMillis()
        seen.entries.removeIf { now - it.value > 60_000L }
        if (seen.put(fingerprint, now) != null) return

        notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }?.let { action ->
            WhatsAppReplyStore.remember(sender, message, action.actionIntent, action.remoteInputs)
        }
        val safeMessage = if (containsSensitiveContent(message)) null else message
        WhatsAppReplyStore.rememberAnnouncement(sender, safeMessage)
        MyraVoiceService.announceWhatsApp(sender, safeMessage)
    }

    private fun isSummary(text: String): Boolean = Regex("^\\d+\\s+(?:new\\s+)?messages?", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun containsSensitiveContent(text: String): Boolean {
        val lowered = text.lowercase(Locale.ROOT)
        val privateWord = Regex("\\b(?:otp|password|passcode|verification\\s+code|security\\s+code|pin)\\b").containsMatchIn(lowered)
        return privateWord || Regex("(?<!\\d)\\d{4,8}(?!\\d)").containsMatchIn(text)
    }

    companion object {
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}

/** Process-local reply targets supplied by WhatsApp notifications. */
object WhatsAppReplyStore {
    data class Result(val message: String, val success: Boolean)
    private data class Target(
        val sender: String,
        val incomingMessage: String,
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val receivedAt: Long
    )

    private val targets = ConcurrentHashMap<String, Target>()
    @Volatile private var latest: Pair<String, String?>? = null

    fun remember(sender: String, message: String, pendingIntent: PendingIntent, inputs: Array<RemoteInput>) {
        targets[normalize(sender)] = Target(sender, message, pendingIntent, inputs, System.currentTimeMillis())
    }

    fun rememberAnnouncement(sender: String, safeMessage: String?) { latest = sender to safeMessage }

    fun latestMessage(): Result {
        val item = latest ?: return Result("Mere notification record mein abhi koi WhatsApp message nahi hai.", false)
        val (sender, message) = item
        return if (message == null) Result("$sender ka private WhatsApp message aaya hai. Main sensitive content aloud nahi padhungi.", true)
        else Result("$sender ka WhatsApp message aaya hai: $message", true)
    }

    fun reply(context: Context, requestedSender: String?, replyText: String): Result {
        val text = replyText.trim()
        if (text.isBlank()) return Result("Message khaali hai, isliye send nahi kiya.", false)
        val now = System.currentTimeMillis()
        targets.entries.removeIf { now - it.value.receivedAt > TARGET_TTL_MS }
        val requested = normalize(requestedSender.orEmpty())
        val target = when {
            requested.isBlank() -> targets.values.maxByOrNull { it.receivedAt }
            else -> targets[requested] ?: targets.values
                .filter { normalize(it.sender).contains(requested) || requested.contains(normalize(it.sender)) }
                .maxByOrNull { it.receivedAt }
        } ?: return Result("${requestedSender ?: "Us message"} ka active WhatsApp reply option nahi mila.", false)

        return try {
            val fillIn = Intent()
            val bundle = Bundle()
            target.remoteInputs.forEach { bundle.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(target.remoteInputs, fillIn, bundle)
            target.pendingIntent.send(context, 0, fillIn)
            Result("${target.sender} ko “$text” WhatsApp reply action se bhej diya.", true)
        } catch (_: PendingIntent.CanceledException) {
            Result("WhatsApp reply expire ho gaya. Naya message aane ke baad try karo.", false)
        }
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private const val TARGET_TTL_MS = 30 * 60 * 1000L
}
