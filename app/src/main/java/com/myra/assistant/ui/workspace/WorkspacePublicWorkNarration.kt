package com.myra.assistant.ui.workspace

internal object WorkspacePublicWorkNarration {
    data class Update(
        val key: String,
        val statusLabel: String,
        val text: String,
    )

    private val hinglish = Regex(
        """(?iu)(?:[\u0900-\u097F]|\b(?:bro|bhai|karo|kro|karna|hai|hain|mein|mai|me|sirf|tak|batao|rakho|hatao|jodo)\b)"""
    )

    fun writeSafety(instruction: String): Update =
        if (isHinglish(instruction)) {
            Update(
                "write-safety",
                "Checking write safety",
                "Repo write check clear hai bro — task feature branch tak locked hai; main/master untouched rahega.",
            )
        } else {
            Update(
                "write-safety",
                "Checking write safety",
                "The write scope is safe: the protected feature-branch binding matches, and this task does not write or merge main/master.",
            )
        }

    fun scope(instruction: String, paths: List<String>): Update {
        require(paths.isNotEmpty() && paths.size <= WorkspaceGitHubSelfEditBatch.MAX_FILES)
        val names = paths.map {
            WorkspaceGitHubWritePolicy.requirePath(it).substringAfterLast('/')
        }
        val target = names.joinToString(", ")
        return if (isHinglish(instruction)) {
            Update(
                "scope",
                if (names.size == 1) "Scoped work to ${names.single()}"
                else "Scoped work across ${names.size} related files",
                if (names.size == 1) {
                    "Scope bhi clear hai bro — $target hi requested task ka target hai. Unrelated working code ko touch nahi karunga."
                } else {
                    "Scope bhi clear hai bro — task ${names.size} bounded files tak limited hai: $target. Iske bahar code touch nahi karunga."
                },
            )
        } else {
            Update(
                "scope",
                if (names.size == 1) "Scoped work to ${names.single()}"
                else "Scoped work across ${names.size} related files",
                if (names.size == 1) {
                    "The scope is clear: this task is bounded to $target, so unrelated working code stays untouched."
                } else {
                    "The scope is clear: this task is bounded to ${names.size} files ($target), with unrelated code left untouched."
                },
            )
        }
    }

    fun proposal(
        instruction: String,
        prepared: WorkspaceGitHubSelfEditBatch.Prepared,
        revised: Boolean = false,
        ciRepair: Boolean = false,
    ): Update {
        val rationale = publicEvidence(prepared.rationale)
        val files = prepared.files.map {
            WorkspaceGitHubWritePolicy.requirePath(it.path).substringAfterLast('/')
        }.joinToString(", ")
        val status = when {
            ciRepair -> "Prepared a CI repair proposal"
            revised -> "Prepared the requested revision"
            else -> "Prepared a change proposal"
        }
        val fallback = if (isHinglish(instruction)) {
            "Proposal ready hai — bounded files: $files. Ab write se pehle review/validation kar raha hoon."
        } else {
            "The change proposal is ready for $files. I’m reviewing it before any protected write."
        }
        val body = rationale?.let {
            if (isHinglish(instruction)) {
                "Proposal ready hai: $it Ab write se pehle review/validation kar raha hoon."
            } else {
                "The change proposal is ready: $it I’m reviewing it before any protected write."
            }
        } ?: fallback
        return Update(
            key = when {
                ciRepair -> "proposal-repair"
                revised -> "proposal-revision"
                else -> "proposal"
            },
            statusLabel = status,
            text = body,
        )
    }

    fun review(
        instruction: String,
        review: WorkspaceGitHubPatchReviewer.Review,
        afterRevision: Boolean,
    ): Update {
        val summary = publicEvidence(review.summary)
            ?: review.risks.asSequence().mapNotNull(::publicEvidence).firstOrNull()
            ?: if (isHinglish(instruction)) "Reviewer ne structured decision return kiya."
            else "The reviewer returned a structured decision."
        val status = WorkspaceAdaptiveWorkUpdate.review(review, afterRevision).label
        val text = when (review.decision) {
            WorkspaceGitHubPatchReviewer.Decision.ACCEPT ->
                if (isHinglish(instruction)) {
                    "Reviewer ne change clear kiya ✅ $summary Ab approved patch ko write stage me le ja raha hoon."
                } else {
                    "The review is clear ✅ $summary I’m moving to the next verified write step."
                }
            WorkspaceGitHubPatchReviewer.Decision.REVISE ->
                if (isHinglish(instruction)) {
                    "Review ne fixable issue pakda: $summary Isi bounded task me ek revision kar raha hoon; abhi write complete nahi hai."
                } else {
                    "The review found a fixable issue: $summary I’m applying one bounded revision; the write is not complete yet."
                }
            WorkspaceGitHubPatchReviewer.Decision.REJECT ->
                if (isHinglish(instruction)) {
                    "Review ne proposed change block kiya: $summary Isliye unsafe write continue nahi karunga."
                } else {
                    "The review blocked the proposed change: $summary I won’t continue with an unsafe write."
                }
        }
        return Update(
            key = "review-" + (if (afterRevision) "second" else "first") +
                "-" + review.decision.name.lowercase(),
            statusLabel = status,
            text = text,
        )
    }

    fun committed(
        instruction: String,
        receipt: WorkspaceGitHubConnector.CommitReceipt,
    ): Update {
        require(receipt.files.isNotEmpty())
        val label = WorkspaceAdaptiveWorkUpdate.committed(receipt).label
        return Update(
            "commit",
            label,
            if (isHinglish(instruction)) {
                "Push confirm ho gaya bro ✅ ${receipt.files.size} file(s) update hui. Ab isi commit ka exact CI dekh raha hoon; GREEN evidence se pehle task done nahi bolunga."
            } else {
                "The push is confirmed ✅ ${receipt.files.size} changed file(s) were committed to the protected feature branch. I’m verifying the exact pushed commit with CI before calling the task complete."
            },
        )
    }

    fun ciRunning(instruction: String, runNumber: Long, status: String): Update {
        require(runNumber > 0)
        val clean = WorkspaceWorkTrace.safeText(status, 32).replace('_', ' ').ifBlank { "running" }
        return Update(
            "ci-running-$runNumber",
            "CI #$runNumber is $clean",
            if (isHinglish(instruction)) {
                "CI #$runNumber ab $clean hai bro. Main isi commit ka terminal result follow kar raha hoon."
            } else {
                "Exact CI #$runNumber is now $clean. I’m following this pushed commit through to its terminal result."
            },
        )
    }

    fun ciFailed(
        instruction: String,
        runNumber: Long,
        summary: String,
        willRepair: Boolean,
    ): Update {
        require(runNumber > 0)
        val reason = publicEvidence(summary)
            ?: if (isHinglish(instruction)) "configured CI ne failure report kiya."
            else "the configured CI reported a failure."
        return Update(
            "ci-failed-$runNumber",
            "CI #$runNumber failed",
            if (isHinglish(instruction)) {
                if (willRepair) {
                    "CI #$runNumber me issue mila: $reason Same bounded task me single repair path use kar raha hoon; result abhi complete nahi hai."
                } else {
                    "CI #$runNumber fail hua: $reason Task complete nahi hai, aur unsafe extra write nahi karunga."
                }
            } else {
                if (willRepair) {
                    "CI #$runNumber found an issue: $reason I’m using the single bounded repair path; the task is not complete yet."
                } else {
                    "CI #$runNumber failed: $reason The task is not complete, and I won’t make an unsafe extra write."
                }
            },
        )
    }

    fun ciPassed(instruction: String, runNumber: Long): Update {
        require(runNumber > 0)
        return Update(
            "ci-passed-$runNumber",
            "CI #$runNumber passed for this commit",
            if (isHinglish(instruction)) {
                "CI #$runNumber GREEN ✅ Isi pushed commit ke configured build/tests pass hue. Ab final verified result wrap up kar raha hoon."
            } else {
                "CI #$runNumber is GREEN ✅ The configured build/tests passed for this pushed commit. I’m preparing the verified completion result now."
            },
        )
    }

    private fun isHinglish(value: String): Boolean = hinglish.containsMatchIn(value)

    private fun publicEvidence(raw: String): String? {
        if (WorkspaceSourceContext.containsPossibleSecret(raw)) return null
        val clean = WorkspaceWorkTrace.safeText(raw, 210).takeIf(String::isNotBlank) ?: return null
        if (Regex("(?i)\\b(?:xkiro|groq|openrouter|qwen|gpt-oss|provider\\s+\\d*|task budget)\\b")
                .containsMatchIn(clean)) return null
        if (Regex("(?i)\\b(?:ci\\s*#?\\d*\\s*(?:passed|green)|build\\s+(?:passed|green|successful)|tests?\\s+(?:passed|green|successful)|merged?|deployed?)\\b")
                .containsMatchIn(clean)) return null
        return clean
    }
}
