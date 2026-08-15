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
        if (!extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY).isNullOrEmpty()) return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val message = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (!isGenuineIncoming(sender, message) || WhatsAppReplyStore.wasJustSentByMyra(message)) return
        val replyAction = notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return
        val remoteInputs = replyAction.remoteInputs ?: return

        val fingerprint = "${normalize(sender)}|${normalize(message)}"
        val now = System.currentTimeMillis()
        seen.entries.removeIf { now - it.value > 60_000L }
        if (seen.putIfAbsent(fingerprint, now) != null) return

        WhatsAppReplyStore.remember(sender, message, replyAction.actionIntent, remoteInputs)
        val safeMessage = if (containsSensitiveContent(message)) null else message
        WhatsAppReplyStore.rememberAnnouncement(sender, safeMessage)
        MyraVoiceService.announceWhatsApp(sender, safeMessage)
    }

    private fun isGenuineIncoming(sender: String, message: String): Boolean {
        if (sender.isBlank() || message.isBlank()) return false
        val title = normalize(sender)
        val body = normalize(message)
        if (title == "you" || title == "whatsapp") return false
        if (body == "checking for new messages" || body == "new messages") return false
        return !Regex("^\\d+\\s+(?:new\\s+)?messages?$").matches(body)
    }

    private fun containsSensitiveContent(text: String): Boolean {
        val lowered = text.lowercase(Locale.ROOT)
        val privateWord = Regex("\\b(?:otp|password|passcode|verification\\s+code|security\\s+code|pin)\\b").containsMatchIn(lowered)
        return privateWord || Regex("(?<!\\d)\\d{4,8}(?!\\d)").containsMatchIn(text)
    }

    private fun userName(context: Context): String {
        val saved = context.getSharedPreferences("myra", Context.MODE_PRIVATE).getString("user_name", null)?.trim()
        return saved?.takeIf { it.isNotBlank() && !it.equals("Friend", ignoreCase = true) } ?: "Zopy"
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()

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
    private val recentOutgoing = ConcurrentHashMap<String, Long>()
    private val recentReplyActions = ConcurrentHashMap<String, Long>()
    @Volatile private var latest: Pair<String, String?>? = null

    fun remember(sender: String, message: String, pendingIntent: PendingIntent, inputs: Array<RemoteInput>) {
        targets[normalize(sender)] = Target(sender, message, pendingIntent, inputs, System.currentTimeMillis())
    }

    fun rememberAnnouncement(sender: String, safeMessage: String?) { latest = sender to safeMessage }

    fun wasJustSentByMyra(message: String): Boolean {
        val now = System.currentTimeMillis()
        recentOutgoing.entries.removeIf { now - it.value > OUTGOING_SUPPRESSION_MS }
        return recentOutgoing.containsKey(normalize(message))
    }

    fun latestMessage(): Result {
        val item = latest ?: return Result("Mere notification record mein abhi koi WhatsApp message nahi hai.", false)
        val (sender, message) = item
        return if (message == null) Result("$sender ka private WhatsApp message aaya hai. Main sensitive content aloud nahi padhungi.", true)
        else Result("$sender ka WhatsApp message aaya hai: $message", true)
    }

    fun reply(context: Context, requestedSender: String?, replyText: String): Result {
        val text = replyText.trim()
        if (text.isBlank()) return Result("Reply command samajh aaya, lekin message clear nahi tha. Dobara bolo: reply karo hi.", false)
        val now = System.currentTimeMillis()
        targets.entries.removeIf { now - it.value.receivedAt > TARGET_TTL_MS }
        val requested = normalize(requestedSender.orEmpty())
        val target = when {
            requested.isBlank() -> targets.values.maxByOrNull { it.receivedAt }
            else -> targets[requested] ?: targets.values
                .filter { normalize(it.sender).contains(requested) || requested.contains(normalize(it.sender)) }
                .maxByOrNull { it.receivedAt }
        } ?: return Result("${requestedSender ?: "Us message"} ka active WhatsApp reply option nahi mila.", false)

        val outgoingKey = normalize(text)
        val actionKey = "${normalize(target.sender)}|$outgoingKey"
        recentReplyActions.entries.removeIf { now - it.value > REPLY_DEDUPE_MS }
        if (recentReplyActions.putIfAbsent(actionKey, now) != null) {
            val name = userName(context)
            return Result("$name, yeh message abhi bheja tha. Maine dobara nahi bheja.", false)
        }
        recentOutgoing[outgoingKey] = now
        return try {
            val fillIn = Intent()
            val bundle = Bundle()
            target.remoteInputs.forEach { bundle.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(target.remoteInputs, fillIn, bundle)
            target.pendingIntent.send(context, 0, fillIn)
            val name = userName(context)
            Result("Done $name, maine ${target.sender} ko “$text” bhej diya hai. Delivery confirm nahi hui hai. Aur kuch karun?", true)
        } catch (_: PendingIntent.CanceledException) {
            recentOutgoing.remove(outgoingKey)
            recentReplyActions.remove(actionKey)
            val name = userName(context)
            Result("Sorry $name, message nahi bhej paayi. WhatsApp reply expire ho gaya. Naya message aane ke baad phir try karna.", false)
        }
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private const val TARGET_TTL_MS = 30 * 60 * 1000L
    private const val OUTGOING_SUPPRESSION_MS = 20_000L
    private const val REPLY_DEDUPE_MS = 12_000L
}
