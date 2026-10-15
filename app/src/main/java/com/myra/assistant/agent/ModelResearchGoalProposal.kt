package com.myra.assistant.agent

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * An ephemeral model suggestion for one READ-ONLY research goal, never execution authority.
 * Android matches it against the authoritative final USER turn and the existing safe search gate.
 */
internal data class ModelResearchGoalProposal(
    val turnId: Long,
    val sourceSpan: String,
    val querySpan: String,
    val confidence: Double
) {
    data class Accepted(val turnId: Long, val request: BrowserSearchRequest)

    companion object {
        fun fromTool(turnId: Long, args: JSONObject): ModelResearchGoalProposal? {
            if (turnId <= 0L || args.keys().asSequence().toSet() !=
                setOf("kind", "source_span", "query_span", "confidence") ||
                args.optString("kind") != "READ_ONLY_RESEARCH"
            ) return null
            val source = args.optString("source_span").trim()
            val query = args.optString("query_span").trim()
            val confidence = args.optDouble("confidence", Double.NaN)
            if (source.length !in 3..500 || query.length !in 3..140 ||
                source.any(Char::isISOControl) || query.any(Char::isISOControl) ||
                !confidence.isFinite() || confidence < .85 || confidence > 1.0) return null
            return ModelResearchGoalProposal(turnId, source, query, confidence)
        }

        private fun canonical(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)
            .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

        private val sensitive = Regex(
            "(?iu)\\b(?:otp|password|passphrase|pin|cvv|api[ -]?key|access[ -]?token|" +
                "private[ -]?key|secret|bank account|card number|aadhaar|aadhar)\\b"
        )
        // Only an optional ANSWER-FORMAT tail may be removed from the literal search topic.
        private val answerOnlySuffix = Regex(
            "(?iu)^(?:and|aur|then|fir)\\s+(?:summari[sz]e|explain|describe|tell|" +
                "batao|batana|brief|show|report)\\b.{0,85}$"
        )

        fun accept(
            proposal: ModelResearchGoalProposal?,
            finalUserText: String,
            finalTurnId: Long,
            decision: AgentTurnDecision
        ): Accepted? {
            if (proposal == null || finalTurnId <= 0L || proposal.turnId != finalTurnId ||
                !decision.authorizesPhoneActions ||
                decision.intent !in setOf(TurnIntent.ACTION_REQUEST, TurnIntent.MULTI_STEP_GOAL)
            ) return null
            // Binding the *whole* final turn prevents a model from ignoring a prohibition
            // outside its chosen short phrase. The model cannot promote conversation to action.
            if (canonical(finalUserText).isBlank() ||
                canonical(finalUserText) != canonical(proposal.sourceSpan) ||
                sensitive.containsMatchIn(finalUserText)) return null
            val parsed = FinalSearchHandoff.parse(finalUserText) ?: return null
            val original = canonical(parsed.query)
            val suggested = canonical(proposal.querySpan)
            if (suggested.isBlank() || !original.contains(suggested)) return null
            if (suggested != original) {
                if (!original.startsWith(suggested + " ")) return null
                val tail = original.removePrefix(suggested).trim()
                if (!answerOnlySuffix.matches(tail)) return null
            }
            // Never let a model silently switch Browser vs YouTube or add an unknown provider.
            return Accepted(finalTurnId, parsed.copy(query = proposal.querySpan.trim()))
        }
    }
}
