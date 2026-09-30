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

    fun events(snapshot: WorkspaceWorkSnapshot): List<WorkspaceWorkEvent> {
        val projected = mutableListOf<WorkspaceWorkEvent>()
        val seenGenericLabels = mutableSetOf<String>()
        val hasExactCiEvidence = snapshot.events.any { raw ->
            raw.presentation == WorkspaceWorkPresentationKind.EVIDENCE &&
                ci.containsMatchIn(raw.label)
        }
        snapshot.events.forEach { raw ->
            if (raw.presentation == WorkspaceWorkPresentationKind.DEFAULT) {
                if (draftPr.containsMatchIn(raw.label) ||
                    completion.containsMatchIn(raw.label) ||
                    raw.label.equals("Preparing result explanation", ignoreCase = true) ||
                    (raw.phase == WorkspaceWorkPhase.VERIFYING &&
                        hasExactCiEvidence && ci.containsMatchIn(raw.label))) {
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
            WorkspaceWorkPhase.THINKING -> "Analyzing the task"
            WorkspaceWorkPhase.SEARCHING,
            WorkspaceWorkPhase.VISITING,
            WorkspaceWorkPhase.READING -> "Reading relevant context"

            WorkspaceWorkPhase.CODING -> when {
                website.containsMatchIn(raw.label) && raw.label.contains("build", ignoreCase = true) ->
                    "Building the requested result"
                applying.containsMatchIn(raw.label) -> "Applying the requested change"
                else -> "Working on the requested change"
            }

            WorkspaceWorkPhase.VERIFYING -> when {
                review.containsMatchIn(raw.label) -> "Reviewing the change"
                ci.containsMatchIn(raw.label) -> "Verifying exact CI"
                writeSafety.containsMatchIn(raw.label) -> "Checking write safety"
                completion.containsMatchIn(raw.label) -> "Confirming completion"
                draftPr.containsMatchIn(raw.label) -> "Updating the draft PR"
                proposal.containsMatchIn(raw.label) -> "Checking the proposed change"
                else -> "Verifying the result"
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
