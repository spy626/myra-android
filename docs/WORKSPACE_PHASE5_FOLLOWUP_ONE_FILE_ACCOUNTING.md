# LYRA Workspace Phase 5 — first bounded natural-language follow-up

Date: 2026-09-18. Base: `707d459080b29ca5d805d855024f01b03e3d0bc1` (private draft recovery; phone evidence subsequently showed suggestion/restore/Apply/Undo and offline exact patch rejection/Apply/Undo, followed by Apply/Keep and saved website preview). This feature is **not phone-accepted** until its own physical-device test. Phase 5 and full autonomous coding remain OPEN.

## Why this slice

After a kept one-file edit, a user should be able to type a **small follow-up about the same approved goal** and select the current file without replacing the project task or building a second chat/planner/router. The follow-up is one-turn, session-only, 180 characters maximum and visible in the local prompt preview and outbound consent. It is not a new task spec or a durable chat history. If the goal/criteria need to change, edit and explicitly reapprove the existing task instead.

## Existing architecture reused

- Keep exactly one `WorkspaceAiHandoff` prompt owner, one `WorkspaceSourceContext` full-file screening/1,500-character excerpt, one `WorkspaceFreeAiSuggestion` HTTPS/free+ZDR request boundary, one `WorkspaceStructuredEdit` untrusted JSON validator, one `WorkspaceScopedEdit` exact-one-file writer and protected Undo/Keep, and the existing private 24-hour suggestion draft store. No new backend, memory database, agent, Android permission, auto-retry, silent route change, billing, or AutoPay.
- The optional follow-up is normalized, length-limited, rejected for control characters and screened with the **same conservative possible-secret patterns** used for source/spec. Pattern screening cannot guarantee privacy. The instruction is JSON-quoted as user text, constrained to the saved goal/criteria and selected file, and shown before Copy or Send. The user still explicitly approves external sharing and later approves a concrete file edit separately.
- Editing the one-turn follow-up invalidates the prepared prompt. Before Copy/Send and on asynchronous response, the screen checks the exact prepared handoff identity, follow-up, current project file full SHA, task ID, spec token, approval and pending rollback. Screen exit cancels in-flight work and clears session key, instruction, prompt and untrusted JSON; validated patch recovery remains private/no-backup and revalidated.

## Verification gates

- JVM: no-note prompt still bounded and unchanged in scope, quoted follow-up and source, no source write during prepare, stale source/paused/revoked task/pending rollback fail closed, oversize/control/possible-secret follow-ups rejected.
- CI: verify final-commit lint, unit tests, debug APK and prerelease. Do not claim success from a queued run.
- Physical phone on a **harmless** project with a current approved/resumed task: after any previous protected edit is kept or undone, type a tiny goal-aligned follow-up, choose one file, review the local prompt and outbound consent including the instruction, approve **one** free request on a no-billing account. If a valid patch arrives, review and separately Apply, then Keep or Undo, and inspect the saved editor/website result. If free/ZDR capacity or output fails, stop without paying or retrying automatically. Record key-free screenshots/video. The patch draft remains recoverable if the screen closes. A screenshot of a correct website is not a general automatic browser/build verification or full product completion.

## Explicitly deferred

Real multi-turn chat history, general natural-language task replanning, multi-file transactions, source graph, automatic build/DOM/visual verification and full autonomous agent remain future work. This small follow-up does not certify any of those capabilities.
