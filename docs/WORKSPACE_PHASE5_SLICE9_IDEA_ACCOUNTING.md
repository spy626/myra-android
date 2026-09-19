# LYRA Workspace Phase 5 slice 9 — review-only project plan outline

Date: 2026-09-17. Baseline: slice 8 normal context refresh flow phone-accepted; direct same-screen external-mutation edge case not phone-tested. Main `WORKSPACE_IDEA_LEDGER.md` and prior accounting addenda are preserved; Phase 5 remains OPEN.

## Saved idea and duplicate check

- MERGE concepts from the existing ledger's AIRI/Plast-Mem PlanSpec and `github/spec-kit` specify → plan → tasks → implement flow into the ONE existing `WorkspaceTaskContract.steps()` template. No upstream code/CLI imported, license or provider dependency added, duplicate planner, model client, router or new database introduced.
- A new optional **Draft project plan (local only)** button appears after explicitly preparing one project-confined file in an approved, unpaused task. The draft binds the saved goal/criteria, task/spec token, selected candidate file and whole-file SHA-256 to the existing inspect/specify/implement/verify steps. It honestly labels all other affected files unknown and the output a deterministic template, NOT AI-generated analysis, permission to edit, or a tested plan.
- Immediately before preparing, use the existing full-file privacy-screened `WorkspaceContextFreshness.check()`; stale/deleted/newly sensitive source, changed spec, revoked approval, paused task or wrong project fail closed. The in-memory view is cleared on task edits, file navigation, recheck and lifecycle refresh. Since other processes can change files after check time, this snapshot is NOT an atomic authorization for future use. No source body is copied into the plan outline, no task JSON/source writes, no network/model request, no app permission, payment or fake verified evidence.
- The existing static execution checklist stays visibly separate from the optional project-bound draft; user cannot approve or execute the outline in this slice. Future real implementation must independently obtain action approval and trusted results.

## Acceptance and remaining work

- JVM tests cover current spec and complete file, reuse of existing contract steps, no writes, changed tail after character 1,500, newly introduced fake API key, revoked/paused/changed spec and wrong project.
- Android CI unit tests and APK required. Physical phone: approved resumed task → Prepare AI context → choose harmless file → Draft project plan → inspect goal, criteria, candidate file, SHA and explicit template/no-edit notice. Edit file in Files then return: old outline clears; prepare context and draft anew. Revoke/Pause clears and hides controls. Phone pass pending until a real recording.
- Deferred, not dropped: trusted and user-reviewed AI-generated plan; plan revision and independent approval; provider-send consent/privacy/verified-recurring-$0 route; scoped per-action edit approvals and rollback; real coding worker, browser/build execution, checkpoints and evidence-based verification. Do NOT claim the whole Phase 5 done.
