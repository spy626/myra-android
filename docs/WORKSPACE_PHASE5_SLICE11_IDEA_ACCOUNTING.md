# LYRA Workspace Phase 5 slice 11 — one-selection local review

Date: 2026-09-17. Baseline: Slice 10's note add/edit, leave-screen clearing, visible-note Revoke and visible-note Pause/Resume flows physically phone-accepted. Phase 5 remains OPEN.

## Saved ideas / reuse and authority

- MERGED the existing `WORKSPACE_IDEA_LEDGER.md` bounded-context + PlanSpec flow and the user's request not to repeat every manual pre-coding button. Reuse the ONE `WorkspaceSourceContext`, `WorkspaceContextFreshness`, `WorkspaceProjectPlanDraft`, task store, selected-file chooser and Activity. No second planner, coordinator, memory store, model client or copied upstream code.
- In an approved and resumed task, **Prepare local review (context + plan)** requests one explicit project-file selection. It then composes existing full-file privacy-screened context with the existing deterministic review-only plan. Both appear together, bound to the same task/spec/path/full-source SHA. No separate plan button tap is required. The old button is retained as optional **Refresh review plan**.
- Failure clears the local context, plan and note instead of showing a half-prepared plan. A final full-file freshness check occurs before displaying the result. The output remains screen-only and transient; concurrent external modifications after a check still require revalidation before any future use. Revoke, Pause, task edits, Files/Preview navigation and lifecycle refresh keep the prior clear-on-change behavior.
- Source text remains untrusted local data and is not copied into the plan. No file writes, uploads, AI inference, action approvals, build, payment or verification occurred. Pattern privacy checks are not a complete secret detector. Planning approval is NOT permission to edit.

## Validation and deferred work

- JVM tests: matching plan/context token and SHA, no task/source writes, changed content after the 1,500-character excerpt, secret content, revoked approval, Pause and wrong project. Android CI unit tests and APK are required before phone distribution.
- Physical-phone acceptance: approved resumed task → **Prepare local review** → select one harmless file → context and plan both show without an extra tap; optional Refresh plan works; add a note and visit Files/return to confirm all clear; reprepare, Revoke and Pause still clear. Phone pass pending until actual video.
- Deferred, NOT dropped: fewer explicit file choices through trustworthy project-level analysis; persistent user-editable AI plan with independent approval; separate provider-send consent with freshly audited recurring $0/no-card privacy terms; per-action scoped edit authorization, backup/rollback, actual coding worker, real browser/build evidence, checkpoints and verification. No claim that Phase 5 is complete.
