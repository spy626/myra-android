# LYRA Workspace Phase 5 slice 4 — explicit local source review

Date: 2026-09-16. **Keep the comprehensive `docs/WORKSPACE_IDEA_LEDGER.md` and `docs/WORKSPACE_PHASE5_SLICE3_IDEA_ACCOUNTING.md`**. This addendum records only the next bounded implementation; all their other saved repo/provider rows remain pending or integrated as stated there. Read all three before the next slice.

## Source and merge decision

- Source: `github/spec-kit` existing-project guide (`docs/guides/existing-projects.md`, main at `45db690bab85924ce53f85a9377076abaca6f1fb`, MIT). The guide says plan against the **existing repository**, reuse architecture, and make one reviewable change. BORROW concept only; no upstream code, CLI or dependency copied.
- LYRA match: the phone-accepted Phase 5 slice 3 already lists project-local filenames; `WorkspaceFileStore.readFile()` already validates relative project paths, symlinks, UTF-8 and 256 KB maximum. `WorkspaceTaskContract` already has one `inspect` step. Reuse these, not another planner, file store, database, indexing engine or model client.
- MERGED: an explicit **Review one source file (read-only)** button presents at most 20 current project file choices and a local text excerpt capped at 1,500 characters. Source is read only after the user selects a currently-listed path; binary/control text and oversized files are refused. The preview is hidden after editing the task, returning to the screen, or refreshing the names list; it is not cached as fresh evidence. The existing saved-task/unsaved-draft guard also applies. No provider sees the text, no additional permissions, model route or fees, and no source changes.
- Verification: JVM regression tests cover project isolation, metadata/symlink denial, stale selections, 20-choice and 1,500-character bounds, binary/oversize rejection, and unchanged task/source bytes. Require green Android lint, unit tests and APK build. Physical-phone result is pending until a real recording or report.

## Still deferred; no fake completion

- A UI source preview is not a trusted executor observation and does not complete `WorkspaceTaskContract` inspect evidence or the execution checklist. Automatic read permission, content-to-model projection, redaction/privacy gate, explicit specification approval, plan generation, file mutation, checkpoint/resume and final verification are **not implemented here**.
- `Graft`, `codebase-memory-mcp`, `context-mode` remain richer bounded project-context candidates subject to exact repo/license/security re-audit; no unverified code is integrated.
- `AIRI/Plast-Mem`, Spec Kit's remaining steps, `browser-use`, screenshot/design skills, model routers/providers and all other main-ledger entries remain at their existing stages. No free provider is asserted verified, no paid fallback/card/AutoPay permitted.

Potential next narrow slice: an explicit, revocable approval of a saved **version-bound** specification, or trusted read-only inspection evidence with provenance and freshness. Choose after this slice's physical phone test, without introducing another LYRA core or premature AI coding claims.
