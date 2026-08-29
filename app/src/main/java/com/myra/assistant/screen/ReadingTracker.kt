package com.myra.assistant.screen

import android.graphics.Rect
import com.myra.assistant.service.AccessibilityHelperService
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class ScreenContentType { ARTICLE, WEB_PAGE, VIDEO_PLATFORM, SOCIAL_FEED, OTHER }
enum class ReadingState { IDLE, READING, WAITING_FOR_SCROLL, SCROLLING, VERIFYING_NEW_CONTENT, PAUSED, COMPLETED, STOPPED }

data class ReadingSegment(val text: String, val fingerprint: String)

data class ReadingSession(
    val readingSessionId: String,
    val screenSessionId: String,
    val pageIdentity: String,
    val url: String? = null,
    val articleTitle: String? = null,
    val foregroundPackage: String,
    val contentType: ScreenContentType,
    val explicitlyRequested: Boolean,
    val state: ReadingState,
    val scrollContainerId: String? = null,
    val readFingerprints: Set<String> = emptySet(),
    val lastVisibleFingerprints: List<String> = emptyList(),
    val currentSectionFingerprint: String? = null,
    val spokenTextFingerprint: String? = null,
    val currentScrollPosition: Int = 0,
    val lastFrameId: Long = 0L,
    val lastAccessibilitySnapshot: Long = 0L,
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
        pageIdentity: String,
        foregroundPackage: String,
        contentType: ScreenContentType,
        explicitlyRequested: Boolean,
        url: String? = null,
        articleTitle: String? = null,
        scrollContainerId: String? = null
    ): ReadingSession? {
        if (!explicitlyRequested || contentType != ScreenContentType.ARTICLE || screenSessionId.isBlank()) return null
        if (foregroundPackage.isBlank() || isVideoOrSocialPackage(foregroundPackage)) return null
        val boundContainer = scrollContainerId?.takeIf(String::isNotBlank) ?: currentArticleContainerId()
        if (boundContainer.isNullOrBlank()) return null
        return ReadingSession(
            UUID.randomUUID().toString(), screenSessionId, pageIdentity, url, articleTitle,
            foregroundPackage, contentType, explicitlyRequested, ReadingState.READING,
            boundContainer
        ).also { session = it }
    }

    @Synchronized fun bindScrollContainer(containerId: String): Boolean {
        val current = session ?: return false
        if (current.state !in setOf(ReadingState.READING, ReadingState.WAITING_FOR_SCROLL)) return false
        if (containerId.isBlank()) return false
        session = current.copy(scrollContainerId = containerId)
        return true
    }

    @Synchronized fun bindCurrentScrollContainer(): Boolean =
        currentArticleContainerId()?.let(::bindScrollContainer) == true

    fun currentBoundScrollContainerId(): String? = session?.scrollContainerId

    fun validateCurrentScrollContainer(): Boolean {
        val current = session ?: return false
        val expected = current.scrollContainerId ?: return false
        val actual = currentArticleContainerId()
        val valid = actual == expected && current.state in setOf(
            ReadingState.READING, ReadingState.WAITING_FOR_SCROLL,
            ReadingState.SCROLLING, ReadingState.VERIFYING_NEW_CONTENT
        )
        if (!valid) {
            AccessibilityHelperService.instance?.let {
                android.util.Log.d(
                    "LyraReading",
                    "ARTICLE_SCROLL_REJECTED reading_session_id=${current.readingSessionId} expected_container=$expected actual_container=${actual.orEmpty()} package=${current.foregroundPackage} reason=container_mismatch"
                )
            }
        }
        return valid
    }

    @Synchronized fun acceptsScrollContainer(containerId: String?, screenSessionId: String, foregroundPackage: String): Boolean {
        val current = session ?: return false
        return current.explicitlyRequested && current.contentType == ScreenContentType.ARTICLE &&
            current.state in setOf(ReadingState.READING, ReadingState.WAITING_FOR_SCROLL) &&
            current.screenSessionId == screenSessionId && foregroundPackage.isNotBlank() &&
            current.foregroundPackage == foregroundPackage &&
            !isVideoOrSocialPackage(foregroundPackage) && !current.scrollContainerId.isNullOrBlank() &&
            current.scrollContainerId == containerId
    }

    @Synchronized fun pause(): Boolean = updateState(ReadingState.PAUSED)
    @Synchronized fun resume(): Boolean {
        val current = session ?: return false
        if (current.state !in setOf(ReadingState.PAUSED, ReadingState.STOPPED)) return false
        session = current.copy(state = ReadingState.READING)
        return true
    }
    @Synchronized fun stop(): Boolean = updateState(ReadingState.STOPPED)
    @Synchronized fun complete(): Boolean = updateState(ReadingState.COMPLETED)
    @Synchronized fun forget() { session = null }

    @Synchronized fun resetProgress(): Boolean {
        val current = session ?: return false
        session = current.copy(state = ReadingState.READING, readFingerprints = emptySet(),
            lastVisibleFingerprints = emptyList(), consecutiveAutoScrollCount = 0, noNewContentCount = 0)
        return true
    }

    @Synchronized fun acceptVisibleText(lines: List<String>, now: Long): List<ReadingSegment> {
        val current = session ?: return emptyList()
        if (current.state !in setOf(ReadingState.READING, ReadingState.VERIFYING_NEW_CONTENT)) return emptyList()
        val segments = lines.asSequence().map(::normalize).filter { it.length >= 24 && !isChrome(it) }
            .distinct().map { ReadingSegment(it, fingerprint(it)) }.toList()
        val fresh = segments.filterNot { it.fingerprint in current.readFingerprints }
        session = current.copy(
            readFingerprints = current.readFingerprints + fresh.map { it.fingerprint },
            lastVisibleFingerprints = segments.map { it.fingerprint },
            currentSectionFingerprint = segments.lastOrNull()?.fingerprint,
            spokenTextFingerprint = fresh.lastOrNull()?.fingerprint ?: current.spokenTextFingerprint,
            noNewContentCount = if (fresh.isEmpty()) current.noNewContentCount + 1 else 0,
            lastReadAt = if (fresh.isEmpty()) current.lastReadAt else now,
            state = ReadingState.READING
        )
        return fresh
    }

    @Synchronized fun recordAutoScroll(): Boolean {
        val current = session ?: return false
        if (!canAutoScroll(current) || !validateCurrentScrollContainer()) return false
        session = current.copy(consecutiveAutoScrollCount = current.consecutiveAutoScrollCount + 1,
            currentScrollPosition = current.currentScrollPosition + 1, state = ReadingState.SCROLLING)
        return true
    }

    @Synchronized fun markWaitingForScroll(): Boolean = transition(ReadingState.READING, ReadingState.WAITING_FOR_SCROLL)
    @Synchronized fun recordObservation(frameId: Long, accessibilityAt: Long) {
        val current = session ?: return
        session = current.copy(lastFrameId = frameId, lastAccessibilitySnapshot = accessibilityAt)
    }
    @Synchronized fun markVerifyingNewContent(frameId: Long, accessibilityAt: Long): Boolean {
        val current = session ?: return false
        if (current.state != ReadingState.SCROLLING) return false
        session = current.copy(state = ReadingState.VERIFYING_NEW_CONTENT, lastFrameId = frameId, lastAccessibilitySnapshot = accessibilityAt)
        return true
    }

    @Synchronized fun pauseIfContextChanged(screenSessionId: String, foregroundPackage: String): Boolean {
        val current = session ?: return false
        if (current.screenSessionId == screenSessionId && current.foregroundPackage == foregroundPackage && validateCurrentScrollContainer()) return false
        session = current.copy(state = ReadingState.PAUSED)
        return true
    }

    fun canAutoScroll(): Boolean = session?.let(::canAutoScroll) == true && validateCurrentScrollContainer()

    private fun canAutoScroll(value: ReadingSession): Boolean =
        value.state in setOf(ReadingState.READING, ReadingState.WAITING_FOR_SCROLL) &&
            value.explicitlyRequested && value.contentType == ScreenContentType.ARTICLE &&
            value.consecutiveAutoScrollCount < maxAutoScrolls && value.noNewContentCount < maxNoNewContent &&
            !value.scrollContainerId.isNullOrBlank() && !isVideoOrSocialPackage(value.foregroundPackage)

    private fun currentArticleContainerId(): String? {
        val service = AccessibilityHelperService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName.isBlank() || isVideoOrSocialPackage(packageName)) return null
        data class ContainerCandidate(val area: Long, val identity: String)
        val candidates = mutableListOf<ContainerCandidate>()
        fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
            if (node.isVisibleToUser && node.isScrollable) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
                    val identity = listOf(
                        packageName,
                        node.viewIdResourceName.orEmpty(),
                        node.className?.toString().orEmpty(),
                        bounds.left.toString(), bounds.top.toString(),
                        bounds.right.toString(), bounds.bottom.toString()
                    ).joinToString("|")
                    candidates += ContainerCandidate(bounds.width().toLong() * bounds.height(), identity)
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::collect)
        }
        collect(root)
        return candidates.maxByOrNull { it.area }?.identity?.let(::containerFingerprint)
    }

    private fun containerFingerprint(identity: String): String =
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }

    private fun updateState(next: ReadingState): Boolean {
        val current = session ?: return false
        session = current.copy(state = next)
        return true
    }

    private fun transition(from: ReadingState, to: ReadingState): Boolean {
        val current = session ?: return false
        if (current.state != from) return false
        session = current.copy(state = to)
        return true
    }

    companion object {
        fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(normalize(value).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        private fun isChrome(value: String): Boolean = value.length < 60 && Regex(
            "^(?:home|menu|search|share|sign in|log in|subscribe|comments?|related|recommended|advertisement|cookie|privacy|next|previous)$"
        ).matches(value)
        private fun isVideoOrSocialPackage(packageName: String): Boolean {
            val p = packageName.lowercase(Locale.ROOT)
            return p == "com.google.android.youtube" || p.contains("youtube") || p.contains("instagram") ||
                p.contains("facebook") || p.contains("tiktok")
        }
    }
}
