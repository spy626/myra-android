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

enum class SemanticPredicate { MOVE_VIEWPORT, OPEN_TARGET, NAVIGATE_BACK, NONE }

data class SemanticCapabilityParse(
    val predicate: SemanticPredicate,
    val request: SemanticTargetRequest,
    val decisionCapability: ToolCapability?
)

/** Separates the sentence's action predicate from spatial target modifiers. */
object SemanticCapabilityParser {
    fun parse(raw: String, working: WorkingTaskContext? = null): SemanticCapabilityParse {
        val normalized = normalize(raw)
        val words = normalized.split(' ').filter(String::isNotBlank)
        val target = SemanticTargetRequestParser.parse(raw, working)
        val opensTarget = words.any { it in OPEN_TARGET }
        val direction = words.any { it in DIRECTION }
        val movement = words.any { it in MOVE } ||
            (direction && (words.any { it in GO } || words.any { it in CONTINUATION }))
        val back = words.any { it in BACK } && words.any { it in GO + OPEN_TARGET }
        val predicate = when {
            opensTarget -> SemanticPredicate.OPEN_TARGET
            back -> SemanticPredicate.NAVIGATE_BACK
            movement -> SemanticPredicate.MOVE_VIEWPORT
            else -> SemanticPredicate.NONE
        }
        val capability = when (predicate) {
            SemanticPredicate.OPEN_TARGET -> ToolCapability.ACCESSIBILITY_CLICK
            SemanticPredicate.NAVIGATE_BACK -> ToolCapability.BACK
            SemanticPredicate.MOVE_VIEWPORT -> ToolCapability.ACCESSIBILITY_SCROLL
            SemanticPredicate.NONE -> null
        }
        return SemanticCapabilityParse(predicate, target, capability)
    }

    fun containsStructuredTranscriptArtifact(raw: String): Boolean =
        Regex("\\{\\s*[a-z_ -]+\\s*}", RegexOption.IGNORE_CASE).containsMatchIn(raw)

    private fun normalize(raw: String) = Normalizer.normalize(raw, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
    private val OPEN_TARGET = setOf("open", "kholo", "khol", "tap", "click", "press", "dabao", "select", "choose", "खोलो", "खोल", "दबाओ", "चुनो")
    private val MOVE = setOf("scroll", "swipe", "स्क्रॉल")
    private val GO = setOf("go", "jao", "jaiye", "jaye", "जाओ", "जाइए", "जाएँ", "चलो")
    private val DIRECTION = setOf("down", "niche", "neeche", "bottom", "up", "upar", "upper", "नीचे", "ऊपर")
    private val CONTINUATION = setOf("more", "further", "thoda", "thora", "aur", "थोड़ा", "थोड़ा", "और")
    private val BACK = setOf("back", "peeche", "piche", "वापस", "पीछे")
}

enum class GroundedActionResultState {
    NOT_DISPATCHED, DISPATCH_ACCEPTED, VERIFIED_SUCCESS, VERIFIED_FAILURE, UNKNOWN
}

/** Model wording never creates evidence that Android performed an action. */
object GroundedActionClaimPolicy {
    private val physicalAction = Regex(
        "\\b(?:tapped|clicked|opened|scrolled|searched|pressed|launched|" +
            "tap\\s+(?:kar|ho)\\s+(?:diya|gaya)|click\\s+(?:kar|ho)\\s+(?:diya|gaya)|" +
            "open\\s+(?:kar|ho)\\s+(?:diya|gaya)|khol\\s+diya|scroll\\s+(?:kar|ho)\\s+(?:diya|gaya)|" +
            "search\\s+(?:kar|ho)\\s+(?:diya|gaya)|दबा\\s+दिया|खोल\\s+दिया|स्क्रॉल\\s+कर\\s+दिया)\\b",
        RegexOption.IGNORE_CASE
    )

    fun containsPhysicalActionClaim(text: String): Boolean = physicalAction.containsMatchIn(text)

    fun shouldSuppress(text: String, state: GroundedActionResultState): Boolean =
        state == GroundedActionResultState.NOT_DISPATCHED && containsPhysicalActionClaim(text)
}

/**
 * The single pre-execution safety gate. It classifies the complete utterance, not isolated
 * app/control keywords. Conservative UNKNOWN meaning stays conversational and cannot operate
 * the phone; semantic/model-backed planning can subsequently promote it through an explicit
 * structured AgentIntent, never through a legacy parser acting on its own.
 */
object UnifiedTurnInterpreter {
    fun interpret(raw: String, working: WorkingTaskContext?, context: CurrentActivityContext? = null): AgentTurnDecision {
        val text = normalize(raw)
        if (text.isBlank()) return conversation(raw)

        if (isCancel(text)) return AgentTurnDecision(TurnIntent.CANCEL, text, authorizesPhoneActions = true, confidence = .99)
        if (isMetaDiscussion(text)) return conversation(raw, .96)
        if (working?.lastRequestedAction != null && isActionFollowUp(text)) {
            return AgentTurnDecision(TurnIntent.FOLLOW_UP, text, requiresPerception = true, confidence = .94)
        }
        val semanticCapability = SemanticCapabilityParser.parse(raw, working)
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
        if (FastVisualRequestClassifier.classify(raw)?.kind == FastVisualKind.QUESTION) {
            return AgentTurnDecision(TurnIntent.SCREEN_QUESTION, text, requiresPerception = true, confidence = .94)
        }
        if (isExplicitMemoryMutation(text)) {
            return AgentTurnDecision(TurnIntent.ACTION_REQUEST, text, authorizesMemoryMutation = true, confidence = .96)
        }
        if (isExplicitSearchGoal(text)) {
            return AgentTurnDecision(TurnIntent.MULTI_STEP_GOAL, text, authorizesPhoneActions = true, requiresPerception = true, confidence = .95)
        }
        if (semanticCapability.decisionCapability != null || isExplicitAction(text)) {
            return AgentTurnDecision(TurnIntent.ACTION_REQUEST, text, authorizesPhoneActions = true, requiresPerception = requiresScreen(text), confidence = .91)
        }
        if (isUniqueCorruptedVisibleTarget(raw, context, working)) {
            return AgentTurnDecision(
                TurnIntent.ACTION_REQUEST, text, authorizesPhoneActions = true,
                requiresPerception = true, confidence = .86
            )
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

    private fun isUniqueCorruptedVisibleTarget(
        raw: String,
        context: CurrentActivityContext?,
        working: WorkingTaskContext?
    ): Boolean {
        if (!SemanticCapabilityParser.containsStructuredTranscriptArtifact(raw)) return false
        val visible = context ?: return false
        val request = SemanticTargetRequestParser.parse(raw, working)
        val hint = request.textHint?.trim()?.takeIf { it.length >= 2 } ?: return false
        val normalizedHint = normalize(hint)
        return visible.visibleElements.count { element ->
            element.actionable && normalize(element.label) == normalizedHint
        } == 1
    }

    private fun isMetaDiscussion(text: String): Boolean {
        val reflective = listOf("main soch", "mai soch", "मैं सोच", "i think", "i was thinking", "लगता", "सोच रहा", "lagta hai", "shayad", "should", "chahiye", "चाहिए")
            .any(text::contains)
        val architectureSubject = listOf("lyra ko", "lyra should", "feature", "फीचर", "system", "सिस्टम", "architecture", "अगर ", "agar ", "if ", "chahiye", "चाहिए")
            .any(text::contains)
        val explicitFirstPerson = text.startsWith("main ") || text.startsWith("mai ") || text.startsWith("मैं ") || text.startsWith("i ")
        return (reflective && architectureSubject) ||
            (explicitFirstPerson && (Regex("\\b(?:soch|think|wonder|shayad|lagta)\\b").containsMatchIn(text) || text.contains("सोच")))
    }

    private fun isExplicitAction(text: String): Boolean {
        val direct = Regex("^(?:please )?(?:lyra )?(?:open|close|launch|start|click|tap|press|scroll|swipe|type|write|send|post|search|find|show|go|play|pause|like|subscribe|read|continue|kholo|khol|dabao|daba do|chalao|likho|bhejo|dhundo|dhoondo|dikhao|niche|neeche|upar|back|home|screen mode|screen sharing|screen vision)\\b")
        val objectFirst = Regex("^(?:chrome|youtube|google|whatsapp|instagram|facebook|settings|video|comment|comments|channel|screen|article|page|इसको|इसे|वीडियो|कमेंट|स्क्रीन)\\b.*\\b(?:kholo|open karo|karo|kar do|dabao|dikhao|search karo|scroll karo|like karo|subscribe karo|send karo|post karo|read karo|padho|ऑन करो|बंद करो|खोलो|दिखाओ)$")
        val devaDirect = Regex("^(?:खोलो|दबाओ|स्क्रॉल|लिखो|भेजो|ढूंढो|दिखाओ|लाइक करो|सब्सक्राइब करो)\\b")
        val contextualImperative = Regex("\\b(?:dabao|press karo|click karo|kholo|khol do|kar do|daba do|दबाओ|खोलो)\\b$").containsMatchIn(text) &&
            Regex("\\b(?:jo|ye|yeh|this|that|isko|usko|wala|wali|जिस|जो|ये|इसको|उसको)\\b").containsMatchIn(text)
        val hindiDirectionalImperative = containsHindiDirection(text) &&
            Regex("(?:जाओ|जाइए|जाएँ|चलो|स्क्रॉल(?: करो)?)$").containsMatchIn(text)
        val directionalContinuation = containsAnyDirection(text) &&
            text.split(' ').any { it in setOf("thoda", "thora", "aur", "थोड़ा", "थोड़ा", "और") }
        val genericTargetImperative = text.split(' ').size >= 2 &&
            Regex("(?:open|kholo|khol do|tap|click|press|dabao|दबाओ|खोलो)$").containsMatchIn(text)
        val contextualSelection = Regex("^(?:ye|yeh|woh|wo|this|that|isko|usko|ये|यह|वो|इसे|इसको)(?: wala| wali)?$").matches(text)
        val navigationImperative = Regex("\\b(?:back|peeche|piche|वापस|पीछे)\\b").containsMatchIn(text) &&
            Regex("(?:go|jao|जाओ|जाइए)$").containsMatchIn(text)
        return direct.containsMatchIn(text) || objectFirst.containsMatchIn(text) || devaDirect.containsMatchIn(text) || contextualImperative || hindiDirectionalImperative || directionalContinuation || genericTargetImperative || contextualSelection || navigationImperative
    }

    private fun isExplicitSearchGoal(text: String): Boolean {
        val searchAction = Regex("\\b(?:search|find|dhundo|dhoondo|khojo|सर्च|ढूंढो|खोजो)\\b").containsMatchIn(text)
        val requested = Regex("\\b(?:karo|kar do|please|करो|कर दो)\\b").containsMatchIn(text)
        return searchAction && requested
    }

    private fun isActionFollowUp(text: String): Boolean = listOf(
        "abhi nahi hua na", "abhi hua kya", "did it work", "did that work", "hua nahi na", "nahi hua na"
    ).any { candidate -> text.trimEnd('?', '.', '!') == candidate }

    private fun isScrollContinuation(text: String): Boolean {
        val words = text.trimEnd('?', '.', '!').split(' ').filter(String::isNotBlank).toSet()
        val latin = words.contains("continue") || words.contains("aur") && words.any { it == "thoda" || it == "thora" }
        val hindi = words.contains("और") && words.any { it == "थोड़ा" || it == "थोड़ा" }
        return latin || hindi
    }

    private fun isExplicitCorrection(text: String): Boolean =
        Regex("^(?:actually|correction|correct|nahi |no |galat |असल में|नहीं )").containsMatchIn(text) &&
            Regex("\\b(?:name|naam|preference|answer|response|called|hai|है)\\b").containsMatchIn(text)

    private fun isExplicitMemoryMutation(text: String): Boolean =
        Regex("^(?:please )?(?:remember|forget|yaad rakho|yaad rakhna|bhool jao|याद रखो|भूल जाओ)\\b").containsMatchIn(text)

    private fun isShortClarification(text: String) = text.split(' ').size <= 8
    private fun requiresScreen(text: String) = Regex("\\b(?:click|tap|press|scroll|swipe|screen|video|button|this|that|ye|isko|usko|wala|comment|like|subscribe)\\b").containsMatchIn(text) || containsHindiDirection(text)
    private fun isQuestion(text: String) = text.endsWith("?") || Regex("^(?:what|why|how|when|where|who|kya|kaise|kyun|kab|kahan|क्या|कैसे|क्यों)\\b").containsMatchIn(text)
    private fun isCancel(text: String) = text in setOf("stop", "cancel", "cancel karo", "rehne do", "chhodo", "mat karo", "रहने दो", "रुको")

    private fun containsHindiDirection(text: String) = text.split(' ').any { it in setOf("नीचे", "ऊपर") }
    private fun containsAnyDirection(text: String) = text.split(' ').any { it in setOf("down", "niche", "neeche", "up", "upar", "नीचे", "ऊपर") }

    private fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}?]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
}
