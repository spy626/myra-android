package com.myra.assistant.screen

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class ScreenContentType { ARTICLE, WEB_PAGE, VIDEO_PLATFORM, SOCIAL_FEED, OTHER }
enum class ReadingState { IDLE, ACTIVE, PAUSED, COMPLETE, STOPPED }

data class ReadingSegment(val text: String, val fingerprint: String)

data class ReadingSession(
    val readingSessionId: String,
    val screenSessionId: String,
    val contentIdentity: String,
    val contentType: ScreenContentType,
    val explicitlyRequested: Boolean,
    val state: ReadingState,
    val readFingerprints: Set<String> = emptySet(),
    val lastVisibleFingerprints: List<String> = emptyList(),
    val consecutiveAutoScrollCount: Int = 0,
    val noNewContentCount: Int = 0,
    val lastReadAt: Long = 0L
)

sealed interface ReadingCommand {
    data object Start : ReadingCommand
    data object Continue : ReadingCommand
    data object Pause : ReadingCommand
    data object Stop : ReadingCommand
    data object Resume : ReadingCommand
    data object StartAgain : ReadingCommand
    data object ReadAgain : ReadingCommand
    data object ReadNewOnly : ReadingCommand
    data object Forget : ReadingCommand
}

object ReadingIntentParser {
    fun parse(raw: String): ReadingCommand? {
        val text = raw.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return when {
            Regex("^(?:stop reading|stop|bas|wait|ruk|ruko|no)$").matches(text) -> ReadingCommand.Stop
            Regex("^(?:pause|pause reading)$").matches(text) -> ReadingCommand.Pause
            Regex("^(?:resume|resume reading)$").matches(text) -> ReadingCommand.Resume
            Regex("^(?:continue|continue reading|read the next section|aage padho|aage padh)$").matches(text) -> ReadingCommand.Continue
            Regex("^(?:start again|read from the beginning|shuru se padho)$").matches(text) -> ReadingCommand.StartAgain
            Regex("^(?:read this part again|dobara padho)$").matches(text) -> ReadingCommand.ReadAgain
            Regex("^(?:read only the new content|sirf naya content padho)$").matches(text) -> ReadingCommand.ReadNewOnly
            Regex("^(?:forget what you read|reading bhool jao)$").matches(text) -> ReadingCommand.Forget
            Regex("\\b(?:read|padh|padho)\\b.*\\b(?:article|page|news|story)\\b|\\b(?:article|page)\\b.*\\b(?:read|padh|padho)\\b").containsMatchIn(text) -> ReadingCommand.Start
            else -> null
        }
    }
}

class ReadingTracker(
    private val maxAutoScrolls: Int = 40,
    private val maxNoNewContent: Int = 2
) {
    @Volatile private var session: ReadingSession? = null

    fun snapshot(): ReadingSession? = session

    @Synchronized fun start(
        screenSessionId: String,
        contentIdentity: String,
        contentType: ScreenContentType,
        explicitlyRequested: Boolean
    ): ReadingSession? {
        if (!explicitlyRequested || contentType != ScreenContentType.ARTICLE || screenSessionId.isBlank()) return null
        return ReadingSession(
            UUID.randomUUID().toString(), screenSessionId, contentIdentity,
            contentType, explicitlyRequested, ReadingState.ACTIVE
        ).also { session = it }
    }

    @Synchronized fun pause(): Boolean = updateState(ReadingState.PAUSED)
    @Synchronized fun resume(): Boolean {
        val current = session ?: return false
        if (current.state !in setOf(ReadingState.PAUSED, ReadingState.STOPPED)) return false
        session = current.copy(state = ReadingState.ACTIVE)
        return true
    }
    @Synchronized fun stop(): Boolean = updateState(ReadingState.STOPPED)
    @Synchronized fun complete(): Boolean = updateState(ReadingState.COMPLETE)
    @Synchronized fun forget() { session = null }

    @Synchronized fun resetProgress(): Boolean {
        val current = session ?: return false
        session = current.copy(
            state = ReadingState.ACTIVE,
            readFingerprints = emptySet(), lastVisibleFingerprints = emptyList(),
            consecutiveAutoScrollCount = 0, noNewContentCount = 0
        )
        return true
    }

    @Synchronized fun acceptVisibleText(lines: List<String>, now: Long): List<ReadingSegment> {
        val current = session ?: return emptyList()
        if (current.state != ReadingState.ACTIVE) return emptyList()
        val segments = lines.asSequence()
            .map(::normalize)
            .filter { it.length >= 24 && !isChrome(it) }
            .distinct()
            .map { ReadingSegment(it, fingerprint(it)) }
            .toList()
        val fresh = segments.filterNot { it.fingerprint in current.readFingerprints }
        session = current.copy(
            readFingerprints = current.readFingerprints + fresh.map { it.fingerprint },
            lastVisibleFingerprints = segments.map { it.fingerprint },
            noNewContentCount = if (fresh.isEmpty()) current.noNewContentCount + 1 else 0,
            lastReadAt = if (fresh.isEmpty()) current.lastReadAt else now
        )
        return fresh
    }

    @Synchronized fun recordAutoScroll(): Boolean {
        val current = session ?: return false
        if (!canAutoScroll(current)) return false
        session = current.copy(consecutiveAutoScrollCount = current.consecutiveAutoScrollCount + 1)
        return true
    }

    fun canAutoScroll(): Boolean = session?.let(::canAutoScroll) == true

    private fun canAutoScroll(value: ReadingSession): Boolean =
        value.state == ReadingState.ACTIVE && value.explicitlyRequested &&
            value.contentType == ScreenContentType.ARTICLE &&
            value.consecutiveAutoScrollCount < maxAutoScrolls && value.noNewContentCount < maxNoNewContent

    private fun updateState(next: ReadingState): Boolean {
        val current = session ?: return false
        session = current.copy(state = next)
        return true
    }

    companion object {
        fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ").trim()

        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(normalize(value).toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        private fun isChrome(value: String): Boolean = value.length < 60 && Regex(
            "^(?:home|menu|search|share|sign in|log in|subscribe|comments?|related|recommended|advertisement|cookie|privacy|next|previous)$"
        ).matches(value)
    }
}
