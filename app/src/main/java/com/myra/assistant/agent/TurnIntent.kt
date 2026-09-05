package com.myra.assistant.agent

import java.text.Normalizer
import java.util.Locale
import com.myra.assistant.screen.FastVisualKind
import com.myra.assistant.screen.FastVisualRequestClassifier

enum class TurnIntent {
    CONVERSATION, QUESTION, SCREEN_QUESTION, ACTION_REQUEST, MULTI_STEP_GOAL,
    FOLLOW_UP, CORRECTION, CLARIFICATION, CANCEL
}

data class AgentTurnDecision(
    val intent: TurnIntent,
    val goal: String,
    val authorizesPhoneActions: Boolean = false,
    val authorizesMemoryMutation: Boolean = false,
    val requiresPerception: Boolean = false,
    val confidence: Double = 0.0
)

/**
 * The single pre-execution safety gate. It classifies the complete utterance, not isolated
 * app/control keywords. Conservative UNKNOWN meaning stays conversational and cannot operate
 * the phone; semantic/model-backed planning can subsequently promote it through an explicit
 * structured AgentIntent, never through a legacy parser acting on its own.
 */
object UnifiedTurnInterpreter {
    fun interpret(raw: String, working: WorkingTaskContext?): AgentTurnDecision {
        val text = normalize(raw)
        if (text.isBlank()) return conversation(raw)

        if (isCancel(text)) return AgentTurnDecision(TurnIntent.CANCEL, text, authorizesPhoneActions = true, confidence = .99)
        if (working?.lastRequestedAction != null && isActionFollowUp(text)) {
            return AgentTurnDecision(TurnIntent.FOLLOW_UP, text, requiresPerception = true, confidence = .94)
        }
        if (isScrollContinuation(text) && working?.lastCompletedTask?.let {
                it.completionState == TaskCompletionState.SUCCESS &&
                    it.action == ToolCapability.ACCESSIBILITY_SCROLL.name &&
                    it.scrollDirection != null
            } == true
        ) {
            return AgentTurnDecision(
                TurnIntent.ACTION_REQUEST, "SCROLL", authorizesPhoneActions = true,
                requiresPerception = true, confidence = .94
            )
        }
        if (isMetaDiscussion(text)) return conversation(raw, .96)
        if (FastVisualRequestClassifier.classify(raw)?.kind == FastVisualKind.QUESTION) {
            return AgentTurnDecision(TurnIntent.SCREEN_QUESTION, text, requiresPerception = true, confidence = .94)
        }
        if (isExplicitMemoryMutation(text)) {
            return AgentTurnDecision(TurnIntent.ACTION_REQUEST, text, authorizesMemoryMutation = true, confidence = .96)
        }
        if (isExplicitSearchGoal(text)) {
            return AgentTurnDecision(TurnIntent.MULTI_STEP_GOAL, text, authorizesPhoneActions = true, requiresPerception = true, confidence = .95)
        }
        if (isExplicitAction(text)) {
            return AgentTurnDecision(TurnIntent.ACTION_REQUEST, text, authorizesPhoneActions = true, requiresPerception = requiresScreen(text), confidence = .91)
        }
        if (working?.unresolvedReference != null && isShortClarification(text)) {
            return AgentTurnDecision(TurnIntent.CLARIFICATION, text, authorizesPhoneActions = true, requiresPerception = true, confidence = .86)
        }
        if (isExplicitCorrection(text)) {
            return AgentTurnDecision(TurnIntent.CORRECTION, text, authorizesMemoryMutation = true, confidence = .86)
        }
        if (isQuestion(text)) return AgentTurnDecision(TurnIntent.QUESTION, text, confidence = .88)
        return conversation(raw)
    }

    private fun conversation(raw: String, confidence: Double = .78) =
        AgentTurnDecision(TurnIntent.CONVERSATION, raw.trim(), confidence = confidence)

    private fun isMetaDiscussion(text: String): Boolean {
        val reflective = listOf("main soch", "mai soch", "i think", "i was thinking", "lagta hai", "shayad", "should", "chahiye")
            .any(text::contains)
        val architectureSubject = listOf("lyra ko", "lyra should", "feature", "system", "architecture", "agar ", "if ")
            .any(text::contains)
        val explicitFirstPerson = text.startsWith("main ") || text.startsWith("mai ") || text.startsWith("i ")
        return (reflective && architectureSubject) ||
            (explicitFirstPerson && Regex("\\b(?:soch|think|wonder|shayad|lagta)\\b").containsMatchIn(text))
    }

    private fun isExplicitAction(text: String): Boolean {
        val direct = Regex("^(?:please )?(?:lyra )?(?:open|close|launch|start|click|tap|press|scroll|swipe|type|write|send|post|search|find|show|go|play|pause|like|subscribe|read|continue|kholo|khol|dabao|daba do|chalao|likho|bhejo|dhundo|dhoondo|dikhao|niche|neeche|upar|back|home|screen mode|screen sharing|screen vision)\\b")
        val objectFirst = Regex("^(?:chrome|youtube|google|whatsapp|instagram|facebook|settings|video|comment|comments|channel|screen|article|page|इसको|इसे|वीडियो|कमेंट|स्क्रीन)\\b.*\\b(?:kholo|open karo|karo|kar do|dabao|dikhao|search karo|scroll karo|like karo|subscribe karo|send karo|post karo|read karo|padho|ऑन करो|बंद करो|खोलो|दिखाओ)$")
        val devaDirect = Regex("^(?:खोलो|दबाओ|स्क्रॉल|लिखो|भेजो|ढूंढो|दिखाओ|लाइक करो|सब्सक्राइब करो)\\b")
        val contextualImperative = Regex("\\b(?:dabao|press karo|click karo|kholo|khol do|kar do|daba do|दबाओ|खोलो)\\b$").containsMatchIn(text) &&
            Regex("\\b(?:jo|ye|yeh|this|that|isko|usko|wala|wali|जिस|जो|ये|इसको|उसको)\\b").containsMatchIn(text)
        return direct.containsMatchIn(text) || objectFirst.containsMatchIn(text) || devaDirect.containsMatchIn(text) || contextualImperative
    }

    private fun isExplicitSearchGoal(text: String): Boolean {
        val searchAction = Regex("\\b(?:search|find|dhundo|dhoondo|khojo|सर्च|ढूंढो|खोजो)\\b").containsMatchIn(text)
        val requested = Regex("\\b(?:karo|kar do|please|करो|कर दो)\\b").containsMatchIn(text)
        return searchAction && requested
    }

    private fun isActionFollowUp(text: String): Boolean = listOf(
        "abhi nahi hua na", "abhi hua kya", "did it work", "did that work", "hua nahi na", "nahi hua na"
    ).any { candidate -> text.trimEnd('?', '.', '!') == candidate }

    private fun isScrollContinuation(text: String): Boolean =
        text.trimEnd('?', '.', '!') in setOf("thoda aur", "thora aur", "aur", "continue")

    private fun isExplicitCorrection(text: String): Boolean =
        Regex("^(?:actually|correction|correct|nahi |no |galat |असल में|नहीं )").containsMatchIn(text) &&
            Regex("\\b(?:name|naam|preference|answer|response|called|hai|है)\\b").containsMatchIn(text)

    private fun isExplicitMemoryMutation(text: String): Boolean =
        Regex("^(?:please )?(?:remember|forget|yaad rakho|yaad rakhna|bhool jao|याद रखो|भूल जाओ)\\b").containsMatchIn(text)

    private fun isShortClarification(text: String) = text.split(' ').size <= 8
    private fun requiresScreen(text: String) = Regex("\\b(?:click|tap|press|scroll|swipe|screen|video|button|this|that|ye|isko|usko|wala|comment|like|subscribe)\\b").containsMatchIn(text)
    private fun isQuestion(text: String) = text.endsWith("?") || Regex("^(?:what|why|how|when|where|who|kya|kaise|kyun|kab|kahan|क्या|कैसे|क्यों)\\b").containsMatchIn(text)
    private fun isCancel(text: String) = text in setOf("stop", "cancel", "cancel karo", "rehne do", "chhodo", "mat karo", "रहने दो", "रुको")

    private fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}?]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
}
