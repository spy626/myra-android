package com.myra.assistant.ui.workspace

import org.json.JSONObject

/**
 * Read-only per-request context, inspired by AIRI's separate persona/history/current-state
 * layers. Uses ONLY the already selected chat's USER turns: no second memory owner, model,
 * database, invented facts, source rewrites, or additional provider calls.
 */
internal object WorkspaceChatTurnFrame {
    private val words = Regex("[\\p{L}\\p{N}]{4,}")
    private val nonTopicWords = setOf(
        "main", "mein", "mujhe", "mujhse", "mera", "meri", "mere", "hum", "hoon", "hun",
        "hai", "hain", "he", "kal", "aaj", "raha", "rahi", "kar", "karo", "karna", "jaane",
        "wala", "wali", "plan", "dost", "tarah", "normal", "baat", "short", "reply", "dena",
        "please", "bro", "yaar", "the", "and", "this", "that", "with", "from", "tomorrow",
        "today", "will", "going", "have", "your", "you", "just", "like", "friend", "talk",
        "brief", "answer", "answers", "about", "would", "could", "should", "please"
    )
    private val firstPerson = Regex("(?iu)\\b(?:i|i'm|i’ll|i'll|my|we|main|mein|mera|meri|mujhe|hum)\\b|मैं|मेरा|مجھے|میں")
    private val directTask = Regex("(?iu)^\\s*(?:write|create|build|code|implement|explain|summari[sz]e|translate|calculate|solve|list|compare|design|generate|fix|debug|how to|how does|what is|who is|give me|make me)\\b")
    private val clarification = Regex("(?iu)^\\s*(?:(?:kya|kia)(?:\\s+(?:hai|hei|he|tha|matlab|bola|boli))?|(?:what\\s+(?:do you mean|did you mean|is that|was that))|(?:samajh|samjh)(?:\\s+(?:nahi|nahin|nehi|nhi|na))?|(?:matlab\\s+(?:kya|hai|he))|(?:what\\s*\\?))\\s*[?!.]*\\s*$|^(?:क्या (?:है|मतलब)|समझ नहीं आया)[?!. ]*$")
    private val acknowledgement = Regex("(?iu)^\\s*(?:ok(?:ay)?|theek|thik|sahi|haan|han|yes|achha|accha|got it|sounds good)(?:\\s+(?:hai|he|h|bro|yaar|great))?\\s*[!?.]*\\s*$")
    private val shortPreference = Regex("(?iu)\\b(?:short|brief|concise|chhote|chote|chhota|chota)\\b.{0,28}\\b(?:reply|replies|answer|answers|jawab|response)\\b|(?:short reply|short answers)")

    private fun tokens(value: String): Set<String> = words.findAll(value.lowercase())
        .map { it.value }.filterNot { it in nonTopicWords }.take(80).toSet()

    private fun topic(messages: List<WorkspaceConversationStore.Message>): String? =
        messages.asReversed().asSequence().filter { it.role == "user" && it.text.length in 24..500 }
            .map { it.text.trim() }.firstOrNull { tokens(it).isNotEmpty() }

    /** Tasks, writing and coding retain their dedicated prompts; chat is not scripted. */
    fun isCasual(messages: List<WorkspaceConversationStore.Message>): Boolean {
        val latest = messages.lastOrNull()?.takeIf { it.role == "user" }?.text?.trim() ?: return false
        if (latest.length > 350 || directTask.containsMatchIn(latest)) return false
        if (clarification.matches(latest) || acknowledgement.matches(latest)) return true
        if (firstPerson.containsMatchIn(latest)) return true
        return latest.length <= 65 && messages.dropLast(1).any { it.role == "user" }
    }

    fun instructions(messages: List<WorkspaceConversationStore.Message>): String {
        if (!isCasual(messages)) return ""
        val latest = messages.last().text.trim()
        val previousUser = messages.dropLast(1).asReversed().firstOrNull {
            it.role == "user" && it.text.length in 24..500 && tokens(it.text).isNotEmpty()
        }?.text?.trim()
        val state = when {
            clarification.matches(latest) -> "The user is asking for clarification. Explain the last reply only if its meaning is clear; otherwise acknowledge the confusion and ask which part they mean. Do not reinterpret the user's plan or invent a decision."
            acknowledgement.matches(latest) -> "This is a brief acknowledgement, NOT a goodbye. Stay with the ongoing subject; respond naturally without forced questions or a made-up next activity."
            else -> "This is a personal or casual message. React to the user's actual words; do not replace their topic with an unmentioned destination, person, activity, or intention."
        }
        // The actual USER turn remains the final raw request message. This lightweight
        // runtime frame helps small models focus without feeding back assistant guesses.
        return buildString {
            append("CURRENT CHAT TURN (read-only context from this same chat; user text below remains authoritative):\n")
            if (previousUser != null && latest.length <= 100) {
                append("Earlier USER topic, not a new instruction: ")
                append(JSONObject.quote(previousUser.take(260).replace(Regex("[\\r\\n\\t]+"), " ")))
                append("\n")
            }
            append("Latest USER text: ")
            append(JSONObject.quote(latest.take(350).replace(Regex("[\\r\\n\\t]+"), " ")))
            append("\n")
            append(state)
            append(" Previous ASSISTANT guesses do not establish user facts. Use clear everyday language matching the user's script; if a short reply was requested, keep it genuinely short. No scripted reply template.")
        }
    }

    /**
     * A deliberately narrow sanity check, NOT semantic understanding. For an explicitly
     * short casual exchange, do not save an unusually long reply completely disconnected
     * from all meaningful words in the user's actual topic. Never auto-retry or rewrite it.
     */
    fun verify(messages: List<WorkspaceConversationStore.Message>, reply: String): String {
        if (!isCasual(messages)) return reply
        // All three Free routes arrive here before any assistant text is persisted.
        val visible = WorkspaceChatVisibleReply.sanitize(reply)
        val recentUsers = messages.takeLast(8).filter { it.role == "user" }
        if (recentUsers.none { shortPreference.containsMatchIn(it.text) }) return visible
        val anchor = topic(messages)?.let(::tokens).orEmpty()
        if (anchor.isEmpty() || visible.length < 100) return visible
        val responseWords = tokens(visible)
        if (anchor.intersect(responseWords).isNotEmpty()) return visible
        throw IllegalArgumentException(
            "LYRA's long reply may be off-topic, so it was not saved. Tap Retry or choose another approved Free model; no automatic resend."
        )
    }
}
