# LYRA Workspace Phase 5 slice 3 — read-only project inspection

Date: 2026-09-16. This addendum belongs beside `docs/WORKSPACE_IDEA_LEDGER.md`, which remains the comprehensive saved idea/provider inventory. **Read both** before the next slice; do not replace the main ledger with this narrow record.

## Source and decision

- Source: `github/spec-kit`, `docs/guides/existing-projects.md` at `45db690bab85924ce53f85a9377076abaca6f1fb`; existing-project guidance calls for a bounded first change and planning against the current repository. MIT project; borrow only the workflow concept. No upstream code, CLI, runtime, files or dependency imported.
- Existing LYRA match: `WorkspaceTaskContract.steps()` already declares an `inspect` step; `WorkspaceFileStore.list()` already performs bounded, project-root-confined directory listing, skips `.lyra` metadata and symlinks. `WorkspaceTaskStore` already owns the saved brief. Reuse these owners; no second planner, repository index, database, personal memory or AI client.
- Integration: a user-triggered **Inspect project files (read-only)** button displays a bounded local snapshot of file/folder names on the task screen. Only the first 20 paths render. No contents read, no provider upload, no file writes, no new permission, no fee. It does **not** persist plan evidence or mark any checklist step complete. An unsaved goal/criteria cannot be silently treated as the saved task. The snapshot is hidden on editing, resume, or saved-task updates and refreshes only on user tap.
- Tests: project isolation, metadata/symlink exclusion, no mutation of source/task metadata, bounded screen output. Require green Android lint, unit tests and APK assembly; phone acceptance requires a real-device test.

## Deferred saved ideas (still tracked in main ledger)

- Spec Kit: clarification, scoped approved plan, tasks, implementation and convergence are **deferred**. No CLI/agent integration.
- AIRI/Plast-Mem: `allowedTools`, expected evidence, reconciler and one-core authority remain as existing contract; capturing actual trusted tool evidence/approval, run checkpoints and verification are **deferred**.
- Graft / codebase-memory-mcp / context-mode: richer project understanding, bounded code context and provenance **deferred** pending exact-repository/license/security review; the current slice only lists names.
- Browser-use, screenshot-to-code, design skills, agents, creative workflows and all other ledger rows keep their existing phase/status; no silent integration.
- Free-model routers/providers stay candidate-only and **unverified**. $0/no card/no AutoPay/no paid fallback remain mandatory. No model routes used in this slice.

Next possible narrow slice: ask for explicit, revocable approval of a saved and version-bound specification or capture trusted read-only file inspection evidence. Neither is implemented here. Inspecting file *names* is not equivalent to inspecting their contents or passing the `inspect` evidence requirement. No autonomous AI coding or phone pass is claimed by this document.
