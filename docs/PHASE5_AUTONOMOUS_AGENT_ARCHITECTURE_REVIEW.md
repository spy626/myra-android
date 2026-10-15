# LYRA Phase 5 — Autonomous Agent Architecture Acceptance Review

Review date: 2026-10-13  
Repository: `spy626/myra-android`  
Review branch: `agent/lyra-rich-blocks-views`  
Pull request: #2 → `agent/myra-phase-1`  
Scope: agent lifecycle, public-source research, cancellation, recovery, one memory owner, and physical acceptance readiness.

## Status

**Code review and targeted hardening have begun; physical Android acceptance is NOT complete.**  
No changes to `main`, no merge, no memory coordinator/database redesign.

The last fully green pre-review head was `8d8700783e65006f36e20434960c50a58f737c89` (push #3949, PR #3950).  
The Phase 5 commits require their own separate CI and real-phone validation. Do not infer phone pass from green GitHub Actions.

## Architecture observed

- `UnifiedLyraAgent.acceptTurn`: interprets final user turns and forwards action authorization to the existing general runtime; ordinary conversation/question/cancel paths lock or cancel actions.
- `GeneralAgentRuntime`: one active general task; synchronizes task start, step claim, verification, source-analysis lease, completion and cancellation. Async task, step and turn IDs fence receipts.
- `MyraVoiceService`: voice/agent bridge; performs approved search and observation steps, then uses the existing read-only public-search and webpage-reading runners. Audio/text delivery is controlled by the voice response arbiter.
- `AutonomousSourceAnalysisPolicy`: independent registrable domains, conservative text overlap, numeric differences and negation checks. This heuristic is **not semantic fact verification** or proof of truth.
- `WorkspacePublicWebSearchRunner` and `WorkspacePublicResearchRunner`: bounded HTTPS discovery and extraction; cancellation generations, validated targets, and no redirected-page relabeling.
- `WorkspaceResearchQueryPlan`: comparison research only, max three derived read-only queries.
- `MemoryBrainCoordinator` / `LyraMemoryDatabase` and `AiriMemoryRuntime`: remain existing memory write/storage owner and task/context projections. Phase 5 has not introduced another memory coordinator, database or model.

## Phase 5 findings and implementation

| ID | Finding | Narrow change | Safety invariant |
| --- | --- | --- | --- |
| P5-01 | Pending research callbacks were fenced by runtime task/turn but not also current voice-response ownership. | `mayDeliverToVoiceTurn` and `MyraVoiceService.ownsTask` guard. | A callback from turn A cannot be delivered into voice turn B. |
| P5-02 | Generic adapter completion could report SUCCESS after verified search + observation even though research-source audit was pending. | `completeFromAdapter` maps this special case to NEEDS_CLARIFICATION. | Only `completeSourceAnalysisIfCurrent` may complete a two-step research goal after source agreement audit. |
| P5-03 | A newer final follow-up could leave old source analysis leased/in flight. | `cancelStaleSourceAnalysis` on final turn; cancel old public search/reader in voice service. | Old research cannot finish or consume network after a newly accepted final turn. |

## Automated regression gates

- Existing source domain and citation tests remain.
- Phase 5: old voice-turn callbacks rejected even while a matching runtime object temporarily exists.
- Phase 5: generic adapter SUCCESS cannot bypass source agreement.
- Phase 5: a new final user turn invalidates old source-analysis lease and stale completion receipts.
- Existing unit tests cover independent source domains, numerically contradictory excerpts, negation mismatches, unrelated sources, unsafe passages and bounded search retries.
- Unit-test/build/signature result must be checked on the **latest branch HEAD**, not an earlier green run.

## Remaining risks / acceptance blockers

1. Cross-site matching is deliberately conservative and lexical: paraphrases may fail to corroborate; matching passages can still share underlying origin or be factually wrong. Do not market this as guaranteed fact checking.
2. A full multi-step answer, contradiction resolution and source trustworthiness require real-world evaluation; web access success alone is insufficient.
3. Voice barge-in, new conversational follow-ups, UI display, lifecycle pause/resume, and actual browser accessibility need physical phone acceptance. CI does not validate those interactions.
4. Verification and cancellation tests are necessary but cannot guarantee all race interleavings across network, Android lifecycle and streaming voice callbacks.
5. Do not merge the PR or promote to a phone-passed baseline before the below tests.

## Physical Android acceptance script (manual)

1. Start a research question; interrupt it with an unrelated new voice question before source reading completes. **Expected:** old research output never appears and no duplicate speech fires.
2. Start research; while pages load, say a clarification or follow-up. **Expected:** old source lease is cancelled; new turn remains responsive.
3. Research two independent pages with agreeing specific facts. **Expected:** cited evidence is shown; success remains qualified, not an invented guarantee.
4. Research opposing numeric or negated statements. **Expected:** conflict marked; task is not completed.
5. Research inaccessible/login/private/redirected pages. **Expected:** no invented fact, credential disclosure or unauthorized navigation; task reports insufficient evidence.
6. Trigger repeat search/retry conditions. **Expected:** no repeated physical action; no more than three approved read-only comparison queries.
7. End/restart voice service during source reading. **Expected:** no late callback or phantom speech after service teardown.
8. Verify existing memory recall/saving and normal conversation still work. **Expected:** no second memory store or unrelated regression.

**Acceptance status:** static review performed, targeted guards committed, latest CI and physical phone tests must be checked separately.
