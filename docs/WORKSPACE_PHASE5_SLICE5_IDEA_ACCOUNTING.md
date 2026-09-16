# LYRA Workspace Phase 5 slice 5 — explicit, revocable, version-bound spec approval

Date: 2026-09-16. **Preserve `docs/WORKSPACE_IDEA_LEDGER.md` and both previous Phase 5 idea-accounting addenda.** The accepted read-only file preview and existing task/criteria are the baseline. This is one narrow next slice, not AI coding.

## Repo idea and existing-code match

- Source: `github/spec-kit`, existing-project guide at `45db690bab85924ce53f85a9377076abaca6f1fb`, MIT; specifically a bounded specification, explicitly agreed guardrails, and reconciliation when specs evolve. BORROW process concept only: no upstream source or CLI copied or installed. Prior ledger lists Spec Kit and AIRI planning approvals as deferred concepts.
- Existing owner: `WorkspaceTaskContract` plus the **one** project-local `.lyra/task.json`, `WorkspaceTaskStore` and `WorkspaceTaskActivity`. MERGED a planning-consent bit into this one task truth, not a second task database, memory brain, planner, provider or work queue.
- `task.json` v3 reads existing v1/v2 tasks as **unapproved**. Each saved specification has a local revision; approval stores a token bound to project, task ID, revision, saved brief and criteria. A nonempty saved criterion and a confirm dialog displaying the complete saved brief and criteria are required. The screen can revoke planning approval explicitly. Saving changed criteria or replacing a goal invalidates approval; unchanged criteria and Pause/Resume preserve it. Stale or wrong-task approval requests reject without writing. Malformed/mismatched approval metadata fails closed to unapproved.
- Approval is **planning consent only**; neither `WorkspaceTaskContract.reconcile()`'s separate implement-step authorization nor trusted executor evidence or final verification is satisfied. No model or network use, automatic read/write, build, terminal, payment or billing is triggered. A hash guards against accidental stale content, not against a malicious party editing private app data.
- Tests: round-trip/relaunch, revoke, pause, empty criteria, stale version and task, change/revert, tampering, legacy v1/v2 compatibility, and dialog/UI guard. Require GitHub Actions unit tests + debug APK and report legacy lint separately; physical Android acceptance is pending until tested on device.

## Deferred, not lost

The full ledger's AIRI/Plast-Mem, Spec Kit remaining stages, safe evidence provenance/freshness, redaction/privacy gate before any source-to-model projection, separate per-action user approval, plan creation, autonomous coding worker, free-only model router, checkpoint/resume, browser tools, impacted tests and final verification **remain deferred**. No provider has been certified $0 by this slice, and no duplicate core/router/memory has been introduced.

Potential next slice after phone acceptance: trusted, project-confined read-only observation evidence with provenance/freshness; do not mark inspect complete from UI preview or treat planning consent as approval to edit.
