#!/usr/bin/env python3
"""Narrow, generic same-chat grounding: no new model, database, provider, or retry."""
from pathlib import Path
root = Path('app/src/main/java/com/myra/assistant/ui/workspace')
tests = Path('app/src/test/java/com/myra/assistant/ui/workspace')

def patch(path, before, after):
    value = path.read_text(encoding='utf-8')
    assert value.count(before) == 1, f'Expected one anchored match in {path}: {before[:100]!r}'
    path.write_text(value.replace(before, after, 1), encoding='utf-8')

policy = r'''package com.myra.assistant.ui.workspace

import java.util.Locale

/** Local, literal recall of what the user wrote in THIS private chat.
 * Never promotes assistant replies, other chats or guessed entities into user evidence.
 * If source selection is ambiguous, it asks rather than inventing an answer.
 * No model call, persistent memory, network access or transcript rewrite.
 */
internal object WorkspaceChatRecallGrounding {
    private const val MAX_QUESTION = 280
    private const val MAX_SOURCE = 600
    private const val MAX_QUOTE = 320
    private val word = Regex("""[\p{L}\p{N}]{2,}""")
    private val englishPast = Regex(
        """(?iu)\b(?:did\s+(?:i|we)\s+(?:say|tell|mention|write|share)|""" +
            """(?:i|we)\s+(?:said|told|mentioned|wrote|shared)|""" +
            """(?:what|where|when|which|who)\s+(?:have|had)\s+(?:i|we)\s+(?:said|told))\b""")
    private val romanPast = Regex(
        """(?iu)\b(?:maine|mene|meine|main\s+ne|humne)\b.{0,140}""" +
            """\b(?:bataya|batayi|bataye|bola|boli|kaha|kahaa|likha|likhi)\b""")
    private val hindiPast = Regex("""(?:मैंने|हमने).{0,140}(?:बताया|बोला|कहा|लिखा)""")
    private val urduPast = Regex("""(?:میں\s+نے|ہم\s+نے).{0,140}(?:بتایا|بولا|کہا|لکھا)""")
    private val question = Regex(
        """(?iu)[?؟]|\b(?:what|where|when|which|who|did|kya|kahan|kahaan|kab|kaun|kis|""" +
            """yaad|remember|remind)\b|क्या|कहाँ|कब|याद|کیا|کہاں|کب""")
    private val ignored = setOf(
        "i", "we", "did", "you", "your", "my", "me", "what", "where", "when", "which", "who",
        "how", "say", "said", "tell", "told", "mention", "mentioned", "write", "wrote", "share",
        "shared", "about", "earlier", "before", "last", "time", "the", "this", "that", "a", "an",
        "maine", "mene", "meine", "main", "ne", "humne", "tumhe", "tumko", "mujhe", "mujhse",
        "kya", "kahan", "kahaan", "kab", "kaun", "kis", "bataya", "batayi", "bataye", "bola",
        "boli", "kaha", "kahaa", "likha", "likhi", "tha", "thi", "the", "hai", "hain",
        "ka", "ki", "ke", "ko", "mein", "me", "pehle", "yaad", "remember", "remind", "mujhko"
    )

    private fun terms(text: String): Set<String> = word.findAll(text.lowercase(Locale.ROOT))
        .map { it.value }.filterNot { it in ignored }.take(70).toSet()

    private fun asksAboutOwnPastWords(questionText: String): Boolean =
        questionText.length in 1..MAX_QUESTION &&
            !questionText.contains('\n') &&
            question.containsMatchIn(questionText) &&
            (englishPast.containsMatchIn(questionText) || romanPast.containsMatchIn(questionText) ||
                hindiPast.containsMatchIn(questionText) || urduPast.containsMatchIn(questionText))

    private fun literalExcerpt(source: String, requested: Set<String>): String? {
        val cleaned = source.trim().replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex(""" {2,}"""), " ")
        if (cleaned.length <= MAX_QUOTE && requested.isEmpty() &&
            cleaned.none(Char::isISOControl)) return cleaned
        // Prefer the relevant literal sentence; never paraphrase or truncate an old turn.
        val sentences = cleaned.split(Regex("""(?<=[.!?।])\s+"""))
            .filter { it.length in 1..MAX_QUOTE && it.none(Char::isISOControl) }
        if (sentences.isEmpty()) return null
        val scored = sentences.map { it to terms(it).count(requested::contains) }
        val best = scored.maxOf { it.second }
        return if (requested.isNotEmpty() && best == 0) null
            else scored.first { it.second == best }.first
    }

    /** Null means an ordinary chat turn, not a recall request. */
    fun answer(messages: List<WorkspaceConversationStore.Message>): String? {
        val latest = messages.lastOrNull()?.takeIf { it.role == "user" }?.text ?: return null
        if (!asksAboutOwnPastWords(latest)) return null
        val candidates = messages.dropLast(1).asReversed().asSequence()
            .filter { it.role == "user" && it.text.length in 1..MAX_SOURCE &&
                it.text.none { char -> char.isISOControl() && char !in "\r\n\t" } }
            .map { it.text.trim() }.filter(String::isNotBlank).distinct().take(80).toList()
        val unknown = "Mujhe is chat mein us baat ka clear user message nahi mila, isliye guess nahi karungi."
        if (candidates.isEmpty()) return unknown
        val query = terms(latest)
        val ranked = candidates.map { it to terms(it).count(query::contains) }
        val best = ranked.maxOf { it.second }
        if (best == 0 && query.isNotEmpty()) return unknown
        val winners = ranked.filter { it.second == best }
        if (winners.size > 1 && query.isNotEmpty())
            return "Is chat mein is topic par ek se zyada baatein hain. Kis wali ki baat kar rahe ho?"
        val source = winners.first().first
        val excerpt = literalExcerpt(source, query) ?: return unknown
        return if (Regex("""(?iu)\b(?:what|where|when|which|who|did|remember)\b""").containsMatchIn(latest))
            "You said: “$excerpt”"
        else "Tumne kaha tha: “$excerpt”"
    }
}
'''
policy_path = root / 'WorkspaceChatRecallGrounding.kt'
assert not policy_path.exists()
policy_path.write_text(policy, encoding='utf-8')

activity = root / 'WorkspaceActivity.kt'
anchor = '        val provider = runCatching { selectedProvider(picked.isNotEmpty()) }\n'
insert = '''        // User-authored recall is answered from the exact selected-chat transcript.
        // Never let a model's earlier guess become evidence; do not spend another Free call.
        if (picked.isEmpty() && projects.getProject(id)?.type == WorkspaceProjectType.CHAT) {
            val grounded = runCatching {
                WorkspaceChatRecallGrounding.answer(conversations.read(id))
            }.getOrNull()
            if (grounded != null) {
                runCatching {
                    require(conversations.read(id).lastOrNull()?.id == stored.id) {
                        "Conversation changed; grounded answer not saved"
                    }
                    conversations.append(id, "assistant", grounded)
                }.onFailure { statusMessage = it.message ?: "Grounded answer could not be saved" }
                render()
                return
            }
        }
'''
patch(activity, anchor, insert + anchor)

gateway = root / 'WorkspaceChatGateway.kt'
anchor = 'internal object WorkspaceChatGateway {\n'
insert = '''internal object WorkspaceChatGateway {
    // Applies across approved Free text routes. It is a general response contract,
    // not a factual memory, prompt-specific location, or claim of verified model output.
    private const val CHAT_REPLY_DISCIPLINE =
        "Respond to the latest user message naturally in the user's language and requested length. " +
            "Stay on its actual topic; do not insert unrelated activities or invented personal events. " +
            "Earlier assistant replies can be mistaken and are NOT evidence of what the user said. " +
            "For questions about the user's earlier words, ground claims only in earlier USER turns " +
            "from this same conversation; quote them if necessary. If evidence is absent, say so " +
            "instead of guessing a name, place, plan or other fact. Never claim phone testing."
'''
patch(gateway, anchor, insert)
patch(gateway, '        val instructions = listOf(writingInstructions, earlier, codeInstructions)\n',
      '        val instructions = listOf(CHAT_REPLY_DISCIPLINE, writingInstructions, earlier, codeInstructions)\n')

old_test = tests / 'WorkspaceChatGatewayTest.kt'
patch(old_test, '        assertEquals("this project only", body.getJSONArray("messages").getJSONObject(0).getString("content"))\n',
      '        val initial = body.getJSONArray("messages")\n        assertEquals("system", initial.getJSONObject(0).getString("role"))\n        assertEquals("this project only", initial.getJSONObject(initial.length() - 1).getString("content"))\n')
patch(old_test, '        assertEquals(2, payload.length())\n', '        assertEquals(3, payload.length())\n')
patch(old_test, '        assertEquals(1, bounded.length())\n        assertEquals(original, bounded.getJSONObject(0).getString("content"))\n',
      '        assertEquals(2, bounded.length())\n        assertEquals(original, bounded.getJSONObject(bounded.length() - 1).getString("content"))\n')
patch(old_test, '        assertEquals("Earlier", openRouter.getJSONObject(0).getString("content"))\n        assertTrue(openRouter.getJSONObject(2).getJSONArray("content").getJSONObject(1)\n',
      '        assertEquals("Earlier", openRouter.getJSONObject(1).getString("content"))\n        assertTrue(openRouter.getJSONObject(3).getJSONArray("content").getJSONObject(1)\n')

new_test = r'''package com.myra.assistant.ui.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceChatRecallGroundingTest {
    private fun u(text: String) = WorkspaceConversationStore.Message("u-$text", "user", text, 1)
    private fun a(text: String) = WorkspaceConversationStore.Message("a-$text", "assistant", text, 2)

    @Test fun explicitRecallQuotesOnlyPreviousUserNotAssistantGuess() {
        val messages = listOf(
            u("Kal main beach jaane ka plan kar raha hoon. Mujhse dost ki tarah normal baat karo, short reply dena."),
            a("You mentioned a trip to Manali."),
            u("Maine kal kahan jaane ka plan bataya tha?"))
        val reply = requireNotNull(WorkspaceChatRecallGrounding.answer(messages))
        assertEquals("Tumne kaha tha: “Kal main beach jaane ka plan kar raha hoon.”", reply)
        assertFalse(reply.contains("Manali"))
    }

    @Test fun worksForDifferentTopicsAndEnglishWithoutPlaceSpecificRules() {
        val history = listOf(u("My friend's name is Kareem and we study together."),
            a("I think the name is Rehan."), u("What did I say about my friend?"))
        assertEquals("You said: “My friend's name is Kareem and we study together.”",
            WorkspaceChatRecallGrounding.answer(history))
        val recipe = listOf(u("I added cardamom to the tea."), a("Nice!"),
            u("What did I tell you about the tea?"))
        assertEquals("You said: “I added cardamom to the tea.”",
            WorkspaceChatRecallGrounding.answer(recipe))
    }

    @Test fun unrelatedRecentUserMessageDoesNotDisplaceRelevantEarlierEvidence() {
        val history = listOf(u("My project is called Bluebird."), a("Cool."),
            u("How is your day?"), a("Good."), u("What did I say about my project?"))
        assertEquals("You said: “My project is called Bluebird.”",
            WorkspaceChatRecallGrounding.answer(history))
    }

    @Test fun competingUserClaimsAskForClarificationInsteadOfPickingAnEntity() {
        val history = listOf(u("Kal park jaane ka plan hai."),
            u("Kal museum jaane ka plan hai."), u("Maine kal kahan jaane ka plan bataya tha?"))
        val answer = requireNotNull(WorkspaceChatRecallGrounding.answer(history))
        assertTrue(answer.contains("ek se zyada"))
        assertFalse(answer.contains("park"))
        assertFalse(answer.contains("museum"))
    }

    @Test fun missingEvidenceDoesNotInventAndNormalTurnsStillUseProvider() {
        assertTrue(requireNotNull(WorkspaceChatRecallGrounding.answer(listOf(
            u("Maine kal kahan jaane ka plan bataya tha?")))).contains("guess nahi"))
        assertTrue(requireNotNull(WorkspaceChatRecallGrounding.answer(listOf(
            u("Hello"), u("Maine kal kahan jaane ka plan bataya tha?")))).contains("guess nahi"))
        assertNull(WorkspaceChatRecallGrounding.answer(listOf(u("Kal main beach jaane ka plan kar raha hoon."))))
        assertNull(WorkspaceChatRecallGrounding.answer(listOf(
            u("Maine kal beach jaane ka plan bataya tha."))))
        assertNull(WorkspaceChatRecallGrounding.answer(listOf(
            u("How should I tell my friend about the trip?"))))
    }

    @Test fun sameChatRequestCarriesLiteralUserEvidenceAndGenericDisciplineToCloudflare() {
        val conversation = listOf(u("I added cardamom to the tea."),
            a("You added cinnamon."), u("What did I tell you about the tea?"))
        val json = JSONObject(WorkspaceChatGateway.openRouterBody(conversation))
        val outgoing = json.getJSONArray("messages")
        val instruction = outgoing.getJSONObject(0).getString("content")
        assertTrue(instruction.contains("assistant replies can be mistaken"))
        assertTrue(instruction.contains("earlier USER turns"))
        assertEquals("I added cardamom to the tea.", outgoing.getJSONObject(1).getString("content"))
        assertEquals("What did I tell you about the tea?", outgoing.getJSONObject(outgoing.length() - 1).getString("content"))
        assertFalse(instruction.contains("cinnamon"))
    }
}
'''
new_test_path = tests / 'WorkspaceChatRecallGroundingTest.kt'
assert not new_test_path.exists()
new_test_path.write_text(new_test, encoding='utf-8')
print('PATCHED: evidence-grounded same-chat recall, ordinary reply discipline, regression tests')
