package com.myra.assistant.screen

import java.util.Locale
import java.text.Normalizer

enum class YouTubeSemanticRole {
    VIDEO_PLAY_SURFACE, VIDEO_TITLE, CHANNEL_PROFILE, CHANNEL_NAME,
    LIKE_BUTTON, COMMENTS_SECTION, SUBSCRIBE_BUTTON, SHARE_BUTTON,
    MORE_ACTIONS, TEXT_INPUT, SEND_COMMENT
}

data class YouTubeSemanticElement(
    val id: String,
    val role: YouTubeSemanticRole,
    val label: String,
    val cardKey: String? = null,
    val top: Int = 0,
    val clickable: Boolean = true,
    val selected: Boolean = false
)

sealed interface YouTubeSemanticResolution {
    data class Selected(val element: YouTubeSemanticElement) : YouTubeSemanticResolution
    data class AlreadyActive(val element: YouTubeSemanticElement) : YouTubeSemanticResolution
    data object Ambiguous : YouTubeSemanticResolution
    data object NotFound : YouTubeSemanticResolution
}

object YouTubeSemanticResolver {
    fun resolveControl(elements: List<YouTubeSemanticElement>, role: YouTubeSemanticRole): YouTubeSemanticResolution {
        val matches = elements.filter { it.clickable && it.role == role }
        if (matches.isEmpty()) return YouTubeSemanticResolution.NotFound
        if (matches.size > 1) return YouTubeSemanticResolution.Ambiguous
        val item = matches.single()
        return if (item.selected && role in setOf(YouTubeSemanticRole.LIKE_BUTTON, YouTubeSemanticRole.SUBSCRIBE_BUTTON)) {
            YouTubeSemanticResolution.AlreadyActive(item)
        } else YouTubeSemanticResolution.Selected(item)
    }

    fun resolveChannel(
        elements: List<YouTubeSemanticElement>,
        requestedName: String?,
        preferProfile: Boolean
    ): YouTubeSemanticResolution {
        val normalizedName = requestedName?.let(::normalize).orEmpty()
        val channelRoles = if (preferProfile) {
            listOf(YouTubeSemanticRole.CHANNEL_PROFILE, YouTubeSemanticRole.CHANNEL_NAME)
        } else listOf(YouTubeSemanticRole.CHANNEL_NAME, YouTubeSemanticRole.CHANNEL_PROFILE)
        val matchingCards = if (normalizedName.isBlank()) {
            elements.filter { it.role in channelRoles }.mapNotNull { it.cardKey }.distinct()
        } else {
            elements.filter { it.role in channelRoles && normalize(it.label).contains(normalizedName) }
                .mapNotNull { it.cardKey }.distinct()
        }
        if (matchingCards.size != 1) return if (matchingCards.isEmpty()) YouTubeSemanticResolution.NotFound else YouTubeSemanticResolution.Ambiguous
        val card = matchingCards.single()
        val selected = channelRoles.asSequence().flatMap { role ->
            elements.asSequence().filter { it.cardKey == card && it.role == role && it.clickable }
        }.firstOrNull() ?: return YouTubeSemanticResolution.NotFound
        return YouTubeSemanticResolution.Selected(selected)
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT)
        // Devanagari vowel signs are Unicode marks, not letters. Keeping them is
        // essential: otherwise "कमेंट" becomes a different token before matching.
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
}

sealed interface YouTubeSemanticCommand {
    data class OpenChannel(val name: String?, val preferProfile: Boolean) : YouTubeSemanticCommand
    data object Like : YouTubeSemanticCommand
    data object OpenComments : YouTubeSemanticCommand
    data object Subscribe : YouTubeSemanticCommand
    data object Share : YouTubeSemanticCommand
    data object More : YouTubeSemanticCommand
    data class TypeText(val payload: String, val replace: Boolean = true) : YouTubeSemanticCommand
    data object SendComment : YouTubeSemanticCommand
    data object CancelComment : YouTubeSemanticCommand
}

object YouTubeSemanticCommandParser {
    fun parse(raw: String): YouTubeSemanticCommand? {
        val trimmed = raw.trim()
        val text = normalize(trimmed)
        when (text) {
            in COMMENT_COMMANDS -> return YouTubeSemanticCommand.OpenComments
            in LIKE_COMMANDS -> return YouTubeSemanticCommand.Like
            in SUBSCRIBE_COMMANDS -> return YouTubeSemanticCommand.Subscribe
        }
        TYPE_PREFIXES.forEach { regex ->
            regex.find(trimmed)?.let { match ->
                val payload = trimmed.substring(match.range.last + 1).trim().trimStart(':', '-', ' ')
                if (payload.isNotBlank()) return YouTubeSemanticCommand.TypeText(payload)
            }
        }
        if (SEND.matches(text)) return YouTubeSemanticCommand.SendComment
        if (CANCEL.matches(text)) return YouTubeSemanticCommand.CancelComment
        if (COMMENTS.containsMatchIn(text)) return YouTubeSemanticCommand.OpenComments
        if (SUBSCRIBE.matches(text)) return YouTubeSemanticCommand.Subscribe
        if (LIKE.matches(text)) return YouTubeSemanticCommand.Like
        if (SHARE.matches(text)) return YouTubeSemanticCommand.Share
        if (MORE.matches(text)) return YouTubeSemanticCommand.More
        val channelMatch = CHANNEL.find(text)
        if (channelMatch != null) {
            val name = channelMatch.groups[1]?.value?.trim()?.takeIf { it.isNotBlank() && it !in GENERIC_CHANNEL }
            return YouTubeSemanticCommand.OpenChannel(name, PROFILE.containsMatchIn(text))
        }
        return null
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()

    private val TYPE_PREFIXES = listOf(
        Regex("^(?:type\\s+karo|comment\\s+mein\\s+likho|isme\\s+type\\s+karo|likho|type\\s+this|nahi\\s+change\\s+karo|replace\\s+karo)\\b\\s*:?[ ]*", RegexOption.IGNORE_CASE)
    )
    private val COMMENT_COMMANDS = setOf(
        "कमेंट ओपन करो", "कमेंट खोलो", "कमेंट दिखाओ",
        "comments kholo", "comment kholo", "comment open karo", "comments open karo",
        "comments dikhao", "comment section kholo"
    )
    private val LIKE_COMMANDS = setOf(
        "video like karo", "video ko like karo", "like karo", "isko like karo",
        "वीडियो लाइक करो", "लाइक करो"
    )
    private val SUBSCRIBE_COMMANDS = setOf(
        "subscribe karo", "channel subscribe karo", "सब्सक्राइब करो", "चैनल सब्सक्राइब करो"
    )
    private val SEND = Regex("^(?:send|post)(?: karo)?$|^comment kar do$")
    private val CANCEL = Regex("^(?:cancel karo|rehne do|chhodo)$")
    // These are evaluated against the raw ASR transcript before brain/display
    // transliteration. Keep Devanagari forms here so they cannot fall through
    // as ordinary conversation.
    private val COMMENTS = Regex("^(?:(?:comments?|comment section|कमेंट्स?|टिप्पणियाँ)(?: ko)? (?:kholo|open karo|open|dikhao)|(?:comments?|comment section|कमेंट्स?|टिप्पणियाँ) kholo|कमेंट(?:्स)? (?:ओपन करो|खोलो|दिखाओ))$")
    private val LIKE = Regex("^(?:(?:video ko |isko )?like(?: karo)?(?: video ko)?|वीडियो लाइक करो|लाइक करो)$")
    private val SUBSCRIBE = Regex("^(?:subscribe(?: karo)?|चैनल सब्सक्राइब करो|सब्सक्राइब करो)$")
    private val SHARE = Regex("^share(?: kholo| open karo| karo)?$")
    private val MORE = Regex("^(?:more|more options|action menu)(?: kholo| open karo)?$")
    private val PROFILE = Regex("\\b(?:profile|avatar|profile pic|channel)\\b")
    private val CHANNEL = Regex("^(?:(.+?) (?:ka |wala )?)?(?:profile pic|profile|channel)(?: click karo| kholo| open karo| open)?$")
    private val GENERIC_CHANNEL = setOf("is video", "video", "current video", "channel")
}

enum class YouTubeCommentState { NONE, COMMENTS_OPEN, EDITING, READY_TO_SEND }

data class YouTubeCommentComposeSnapshot(
    val packageName: String,
    val windowId: Int?,
    val generation: Long,
    val fieldIdentity: String? = null,
    val draft: String = "",
    val state: YouTubeCommentState = YouTubeCommentState.NONE
)

class YouTubeCommentComposeTracker {
    private var value: YouTubeCommentComposeSnapshot? = null
    fun snapshot(): YouTubeCommentComposeSnapshot? = value
    fun commentsOpened(packageName: String, windowId: Int?, generation: Long) {
        value = YouTubeCommentComposeSnapshot(packageName, windowId, generation, state = YouTubeCommentState.COMMENTS_OPEN)
    }
    fun draftSet(packageName: String, windowId: Int?, generation: Long, fieldIdentity: String, draft: String): Boolean {
        if (!owns(packageName, windowId, generation) || draft.isBlank()) return false
        value = value!!.copy(fieldIdentity = fieldIdentity, draft = draft, state = YouTubeCommentState.READY_TO_SEND)
        return true
    }
    fun canSend(packageName: String, windowId: Int?, generation: Long): Boolean =
        owns(packageName, windowId, generation) && value?.state == YouTubeCommentState.READY_TO_SEND && value?.draft?.isNotBlank() == true
    fun cancel() { value = null }
    fun invalidateUnless(packageName: String, windowId: Int?, generation: Long) {
        if (!owns(packageName, windowId, generation)) value = null
    }
    private fun owns(packageName: String, windowId: Int?, generation: Long): Boolean {
        val current = value ?: return false
        return current.packageName == packageName && current.windowId == windowId && current.generation == generation
    }
}
