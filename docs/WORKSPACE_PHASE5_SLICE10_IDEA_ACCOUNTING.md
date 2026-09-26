# LYRA Workspace Phase 5 slice 10 — user plan-scope review note

Date: 2026-09-17. Baseline: Slice 9's plan, edit-refresh, revoke and Pause/Resume flows physically phone-accepted. Phase 5 is OPEN.

## Saved ideas / duplicate and authority check

- MERGE one concept from the existing `WORKSPACE_IDEA_LEDGER.md` AIRI/Plast-Mem user plan feedback and `github/spec-kit` plan-review/revision flow into the existing `WorkspaceProjectPlanDraft` and `WorkspaceTaskActivity`. The phase-9 deterministic outline remains an honest TEMPLATE, not an AI-generated implementation plan. No upstream code, dependency or third-party permissions were imported.
- After an approved, unpaused task's selected-file context and read-only plan are visible, the user may type **one 1–500-character plan scope note** (for example, a requested constraint). The locally displayed note binds project, task, exact spec token, selected path and complete source SHA-256 to the current plan. Full-file freshness and the existing privacy gate are checked again before accepting it. Blank/oversized notes and common key-assignment or private-key patterns are rejected; pattern screening is not a complete secret detector.
- Explicitly SCREEN ONLY and NOT SAVED: a note is cleared when the task is edited, context or plan is rechecked, a different file is selected, Files/Preview is visited, the Activity resumes, approval is revoked, or the task is paused. No note is written into task JSON or any project source. No new planner, memory brain, model/provider, network route, paid service, AI run or file mutation is introduced.
- This is user feedback and NOT approval of a generated plan or an individual code edit. The original task's saved planning approval is separate. No tool/evidence/verification statuses are changed. The phone UI must visibly say notes are not saved so the user can copy them before leaving.

## Checks and deferred work

- JVM tests: note normalization, 500-character bound, secret-pattern rejection, unchanged task/source after review, correct project/spec/source hash binding, and failures on altered source content past the 1,500-character preview, wrong plan, pause or revocation. Dialog-appearance regression covers the new themed dialog.
- CI must pass unit tests and debug APK before sharing; physical Android acceptance remains pending until the user tests new note, note edit, file edit/return, approval revoke and Pause/Resume. This is no evidence of AI generation or automatic changes.
- DEFERRED (not dropped): persistent, user-editable AI-generated plan revisions and independent plan approval; explicit provider-send consent and live-audited recurring $0/no-card route; per-file proposed diff, user action authorization, backup/rollback, real coding worker, browser/build tool evidence, checkpoints and final verification. Never claim all of Phase 5 is done.
