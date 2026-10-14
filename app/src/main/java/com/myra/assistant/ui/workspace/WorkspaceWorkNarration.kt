package com.myra.assistant.ui.workspace

/**
 * Human-facing projection of the observable work trace.
 *
 * Raw WorkspaceWorkEvent entries remain untouched for debugging, budgets, provider receipts and
 * exact verification evidence. This projection only controls the normal Chat presentation: it
 * groups noisy implementation events into a few meaningful milestones without inventing work.
 */
internal object WorkspaceWorkNarration {
    private val review = Regex("(?i)review|reviewer")
    private val ci = Regex("(?i)\\bci\\b|actions")
    private val budget = Regex("(?i)^task budget$")
    private val writeSafety = Regex("(?i)protected write lane")
    private val completion = Regex("(?i)completion criteria")
    private val proposal = Regex(
        "(?i)patch accepted|code proposal|generated website|website files|saved edit"
    )
    private val draftPr = Regex("(?i)draft pr")
    private val resume = Regex("(?i)resum|re-read|checkpoint")
    private val switching = Regex("(?i)switch|provider|route|compatible free format")
    private val repair = Regex("(?i)repair|revision|failed")
    private val website = Regex("(?i)website")
    private val github = Regex("(?i)github|self-edit|\\bci\\b")
    private val edit = Regex("(?i)edit")
    private val applying = Regex("(?i)preparing|applying|saving|editing|committing|building")
    // Only the observed event can supply a progress milestone. Do not infer
    // search counts, source domains, research conclusions or private reasoning.
    private val publicPageRead = Regex("""(?i)^Reading public page (\d{1,2}) of (\d{1,2})$""")
    private val publicWeb = Regex("(?i)public web|web search|website|webpage|web link")
    private val missingResearch = Regex("(?i)missing source evidence|additional source search")
    private val sourceAudit = Regex("(?i)research answer evidence audit")
    private val sourceLink = Regex("(?i)verifying direct destination|verifying source|checking source")
    private val questionThinking = Regex("(?i)^thinking$|understanding your question")
    private val researchWriting = Regex("(?i)synthesizing verified source evidence")


    fun events(snapshot: WorkspaceWorkSnapshot): List<WorkspaceWorkEvent> {
        val projected = mutableListOf<WorkspaceWorkEvent>()
        val seenGenericLabels = mutableSetOf<String>()
        val hasExactCiEvidence = snapshot.events.any { raw ->
            raw.presentation == WorkspaceWorkPresentationKind.EVIDENCE &&
                ci.containsMatchIn(raw.label)
        }
        val hasReviewEvidence = snapshot.events.any { raw ->
            raw.presentation == WorkspaceWorkPresentationKind.EVIDENCE &&
                review.containsMatchIn(raw.label)
        }
        snapshot.events.forEach { raw ->
            if (raw.presentation == WorkspaceWorkPresentationKind.DEFAULT) {
                if (draftPr.containsMatchIn(raw.label) ||
                    completion.containsMatchIn(raw.label) ||
                    raw.label.equals("Preparing result explanation", ignoreCase = true) ||
                    // Query completion has its own verified page-count receipt;
                    // don't render technical bookkeeping as another generic row.
                    raw.label.equals("Web candidates collected", ignoreCase = true) ||
                    (raw.phase == WorkspaceWorkPhase.VERIFYING &&
                        hasExactCiEvidence && ci.containsMatchIn(raw.label)) ||
                    (raw.phase == WorkspaceWorkPhase.VERIFYING &&
                        hasReviewEvidence && review.containsMatchIn(raw.label))) {
                    return@forEach
                }
            }
            val event = narrate(raw) ?: return@forEach
            val previous = projected.lastOrNull()
            if (previous != null &&
                previous.phase == event.phase &&
                previous.label == event.label &&
                previous.detail == event.detail) {
                return@forEach
            }
            if (event.presentation == WorkspaceWorkPresentationKind.DEFAULT &&
                event.detail == null &&
                !seenGenericLabels.add(event.label)) {
                return@forEach
            }
            projected += event
        }
        return projected
    }

    private fun narrate(raw: WorkspaceWorkEvent): WorkspaceWorkEvent? {
        if (raw.presentation == WorkspaceWorkPresentationKind.EVIDENCE) {
            return raw
        }
        if (budget.matches(raw.label)) return null
        val label = when (raw.phase) {
            WorkspaceWorkPhase.THINKING -> when {
                researchWriting.containsMatchIn(raw.label) -> "Preparing the sourced answer"
                questionThinking.containsMatchIn(raw.label) -> "Thinking"
                else -> "Analyzing the task"
            }

            WorkspaceWorkPhase.SEARCHING -> when {
                missingResearch.containsMatchIn(raw.label) -> "Searching for missing sources"
                raw.label.contains("GitHub", ignoreCase = true) -> "Searching GitHub"
                publicWeb.containsMatchIn(raw.label) ||
                    raw.label.contains("searching the web", ignoreCase = true) ->
                    "Searching websites"
                else -> "Searching relevant sources"
            }

            WorkspaceWorkPhase.VISITING -> when {
                publicWeb.containsMatchIn(raw.label) ||
                    raw.label.contains("destination", ignoreCase = true) ->
                    "Opening website"
                else -> "Reading relevant context"
            }

            WorkspaceWorkPhase.READING -> when {
                publicWeb.containsMatchIn(raw.label) ||
                    raw.label.contains("public page", ignoreCase = true) ->
                    "Reading webpage"
                else -> "Reading relevant context"
            }

            WorkspaceWorkPhase.CODING -> when {
                website.containsMatchIn(raw.label) && raw.label.contains("build", ignoreCase = true) ->
                    "Building the requested result"
                applying.containsMatchIn(raw.label) -> "Applying the requested change"
                else -> "Working on the requested change"
            }

            WorkspaceWorkPhase.VERIFYING -> when {
                publicPageRead.matches(raw.label) -> {
                    val match = requireNotNull(publicPageRead.matchEntire(raw.label))
                    "Reading website " + match.groupValues[1] + " of " + match.groupValues[2]
                }
                sourceAudit.containsMatchIn(raw.label) -> "Checking answer citations"
                sourceLink.containsMatchIn(raw.label) -> "Checking source links"
                review.containsMatchIn(raw.label) -> "Reviewing the change"
                ci.containsMatchIn(raw.label) -> "Verifying exact CI"
                writeSafety.containsMatchIn(raw.label) -> "Checking write safety"
                completion.containsMatchIn(raw.label) -> "Confirming completion"
                draftPr.containsMatchIn(raw.label) -> "Updating the draft PR"
                proposal.containsMatchIn(raw.label) -> "Checking the proposed change"
                else -> "Checking completed work"
            }

            WorkspaceWorkPhase.RECOVERING -> when {
                resume.containsMatchIn(raw.label) -> "Resuming the task"
                switching.containsMatchIn(raw.label) -> "Switching to an available route"
                repair.containsMatchIn(raw.label) -> "Repairing the task"
                else -> "Recovering the task"
            }

            WorkspaceWorkPhase.DONE -> when {
                website.containsMatchIn(raw.label) -> "Website verified"
                github.containsMatchIn(raw.label) -> "GitHub change verified"
                edit.containsMatchIn(raw.label) -> "Change verified"
                else -> "Work verified"
            }

            WorkspaceWorkPhase.ERROR -> "Work stopped"
        }

        val detail = if (raw.phase == WorkspaceWorkPhase.ERROR) {
            raw.detail ?: raw.label.takeUnless { it.equals("Work stopped", ignoreCase = true) }
        } else null

        return WorkspaceWorkEvent(
            phase = raw.phase,
            label = label,
            detail = detail,
            atMs = raw.atMs,
        )
    }
}
