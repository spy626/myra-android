from pathlib import Path

ROOT = Path('app/src/main/java/com/myra/assistant/ui/workspace')

def patch(name, before, after):
    file = ROOT / name
    content = file.read_text()
    hits = content.count(before)
    if hits != 1:
        raise RuntimeError(f'{name}: expected one patch anchor, found {hits}')
    file.write_text(content.replace(before, after, 1))

patch('WorkspaceChatTurnFrame.kt',
'''        val visible = WorkspaceChatVisibleReply.sanitize(reply)
        val recentUsers = messages.takeLast(8).filter { it.role == "user" }
''',
'''        val visible = WorkspaceCasualReplyEvidence.verify(messages,
            WorkspaceChatVisibleReply.sanitize(reply))
        val recentUsers = messages.takeLast(8).filter { it.role == "user" }
''')

patch('WorkspaceChatTurnFrame.kt',
'''    private val directTask = Regex("(?iu)^\\\\s*(?:write|create|build|code|implement|explain|summari[sz]e|translate|calculate|solve|list|compare|design|generate|fix|debug|how to|how does|what is|who is|give me|make me)\\\\b")
''',
'''    private val romanHindi = Regex("(?iu)\\\\b(?:main|maine|mujhe|mera|meri|kal|kya|sahi|jaunga|jaungi|jaane|nahi|hai|bataya)\\\\b")
    private val directTask = Regex("(?iu)^\\\\s*(?:write|create|build|code|implement|explain|summari[sz]e|translate|calculate|solve|list|compare|design|generate|fix|debug|how to|how does|what is|who is|give me|make me)\\\\b")
''')

patch('WorkspaceChatTurnFrame.kt',
'''            append(state)
            append(" Previous ASSISTANT guesses do not establish user facts. Use clear everyday language matching the user's script; if a short reply was requested, keep it genuinely short. No scripted reply template.")
''',
'''            append(state)
            if (messages.asReversed().filter { it.role == "user" }.take(3)
                    .any { romanHindi.containsMatchIn(it.text) }) {
                append(" USER LANGUAGE: Reply in natural Roman Hindi/Hinglish in Latin letters, " +
                    "not invented slang or Devanagari. Use grammatically complete everyday " +
                    "phrases, consistent speaker perspective and normal Hindi verb forms. " +
                    "Never output broken or clipped words or random English fragments.")
            }
            append(" An earlier ASSISTANT reply can be mistaken: do not use its guesses as " +
                "user facts. For short acknowledgements and clarifications, never add an " +
                "unmentioned name, venue or a plan to meet. Correct any unsupported prior " +
                "assistant claim instead of compounding it. Keep short replies brief and " +
                "complete; do not use a canned response template.")
''')

patch('WorkspaceChatPlanStatus.kt',
    r'\b.{0,25}\b(?:jaa?unga|jaa?ungi|jaa?enge|going|go|attend|visit|karunga|karungi)',
    r'\b.{0,25}?\b(?:jaa?unga|jaa?ungi|jaa?enge|going|go|attend|visit|karunga|karungi)')

patch('WorkspaceChatPlanStatus.kt',
'''        if (plan.containsMatchIn(text) && decision.containsMatchIn(text) &&
            withholding.containsMatchIn(text)) return Evidence(State.DECIDED_UNDISCLOSED, text)
''',
'''        val negativeSpans = negatedAction.findAll(text).map { it.range }.toList()
        val disclosedAction = affirmativeAction.findAll(text).any { action ->
            negativeSpans.none { action.range.first in it }
        }
        if (plan.containsMatchIn(text) && decision.containsMatchIn(text) &&
            withholding.containsMatchIn(text) && !disclosedAction)
            return Evidence(State.DECIDED_UNDISCLOSED, text)
''')

patch('WorkspaceChatGateway.kt',
'''            "when the user asks for a short reply. Use complete everyday words, not clipped " +
            "fragments. If a plan is cancelled without a replacement being shared, say only " +
''',
'''            "when the user asks for a short reply. In Roman Hindi/Hinglish, use natural " +
            "grammar and complete familiar words; no fake slang or arbitrary proper names. " +
            "Do not propose meeting the user unless invited. If a plan is cancelled " +
            "without a replacement being shared, say only " +
''')

print('Applied only shared Chat response, plan-state, and Roman Hindi wording changes')
