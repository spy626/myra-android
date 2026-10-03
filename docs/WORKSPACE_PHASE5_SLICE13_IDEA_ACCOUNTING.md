# LYRA Workspace Phase 5 slice 13 — structured patch authority boundary

Date: 2026-09-17. Base: `5c4b9a63931be6b5793274b6a309f0199010f0ca` (Slice 12 requested phone flows physically accepted). Phase 5 remains OPEN.

## Saved ideas checked; exact implementation match

- Reused the existing `WORKSPACE_IDEA_LEDGER.md` concepts instead of creating another coding brain: AIRI/Plast-Mem PlanSpec authority separation, `github/spec-kit` specify/plan/implement separation, bounded source context, and the structured-tool/output concept collected from `microsoft/generative-ai-for-beginners`. No upstream source code was copied and no second planner, coordinator, model router, personal-memory DB or file writer was added.
- Slice 13 adds a strict **offline structured edit envelope** for the future coding model. Accepted schema is version 1, operation `replace_exact_once`, with only `path`, `oldText`, `newText` and bounded `rationale`. Unknown fields, invalid JSON, unsupported operations, ambiguous source text and unavailable paths fail closed.
- Every model-style field is untrusted data. LYRA independently reloads the current approved/resumed task, checks the path against the existing eligible project-file list, re-runs full-file privacy screening, computes the current full-file SHA-256 and builds the trusted local source context. A model cannot provide or override the trusted spec token or source fingerprint.
- Validation and preview perform no write. Applying requires a new exact-file confirmation showing the current SHA and old/new text, then delegates to the already phone-accepted `WorkspaceScopedEdit` executor. The existing private rollback, post-write SHA check, Undo/Keep behavior and later-manual-change protection stay the single mutation truth.
- The current repository already contains Gemini/API-key infrastructure for normal LYRA operation, but Slice 13 deliberately does **not** connect Workspace to any provider. The provider/router candidates in the ledger are still unverified for current recurring-$0/no-card/privacy constraints. No network request, source upload, new API key, paid fallback or model inference is introduced here.

## Validation and phone gate

- JVM tests cover: a valid structured draft remains read-only until the existing Safe Edit executor is explicitly called; local path/source grounding; missing or ambiguous exact text; unsupported extra model fields; Pause/Revoke; full-file secret screening; and blocking another patch while a rollback slot is pending.
- Android CI lint/unit tests/debug APK and prerelease must pass on the final Slice 13 head before phone distribution.
- Physical phone acceptance: approved/resumed harmless website task → Project Home → **Structured AI patch (offline trial)** → paste one valid JSON replacement for an exact unique current `index.html` snippet → validate/preview (file must remain unchanged) → review exact write permission → apply → protected rollback appears → Undo restores the original. Then paste an envelope containing an unsupported field such as `"shell":"..."`; LYRA must refuse it without changing the file.
- **This proves the model-output-to-trusted-executor boundary, not autonomous AI coding.** Direct provider send, freshly audited recurring-$0/no-card route, project-level file selection/impact analysis, AI-generated multi-file transactions, build/browser evidence, checkpoints and final verified completion remain DEFERRED, not dropped.
