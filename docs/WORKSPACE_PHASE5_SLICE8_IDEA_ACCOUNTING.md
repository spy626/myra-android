# LYRA Workspace Phase 5 slice 8 — explicit local context freshness recheck

Date: 2026-09-17. Baseline: slice 7 local context and fake-secret blocking phone-accepted. This slice needs its own physical-phone check. Preserve `WORKSPACE_IDEA_LEDGER.md` and slice 3–7 addenda; Phase 5 remains OPEN.

## Saved idea / existing owner

- BORROW the saved ledger's AIRI/Plast-Mem provenance, bounded context and verification-gate concept, merged into the existing `WorkspaceSourceContext`/`WorkspaceSourcePreview` and one project task screen. Existing scoped `WorkspaceFileStore` owns reading, `WorkspaceTaskStore` owns spec/approval. No third-party code, new dependency, extra coordinator, model client, personal memory or router.
- A manual **Recheck context freshness (local only)** control appears only after the user explicitly prepares one allowed file for an approved, unpaused task. The recheck rereads the full project-confined text through the existing bounded privacy-screened reader, revalidates saved task identity/spec approval/paused state and compares full-file SHA-256 plus bounded excerpt against the displayed draft. Matching means only that bytes/spec were the same *at check time*, not that source is trusted or the view stays live.
- If the file is edited even after character 1,500, removed, newly contains a detected secret, or the task is paused/revoked/changed, the old context text and in-memory draft are dropped and a generic stale/blocked message asks the user to prepare again. Navigating away, changing spec text or replacing the displayed view also clears the draft. Nothing is sent to any model, written to workspace/task JSON or counted as trusted evidence. Existing approval is never promoted into permission for network, tools, edits, build, or payment.
- Pattern screening remains incomplete; this is NOT a provider-send authorization, atomic freshness at future send time, or autonomous execution. A future trusted run must independently recheck immediately before any separately consented transfer and must obey verified $0 route and privacy policy.

## Test and phone gate

- JVM coverage: same full content/spec, source change *beyond* the visible excerpt, deletion, approval revoke, Pause, criteria change, wrong project and newly introduced fake credential outside excerpt; task JSON and source remain unchanged on a matching recheck. Existing dialog appearance tests must remain green.
- Physical phone: prepare harmless `index.html`, tap Recheck → same-content notice; edit source *after* first 1,500 characters in Files, return and prepare context again then recheck if screen navigation clears it; for a directly visible stale draft edit source via an independent workflow without leaving the task screen, if possible. Revoke or Pause must clear the draft and hide the control. In all cases no AI upload or file write by recheck. Do not claim a phone pass from CI.

## Deferred, not lost

User-reviewed, route-specific and revocable provider-send consent; one-run trusted observation/provenance and immediate pre-send freshness; privacy audit and redaction, free-only provider/account routing (no card, AutoPay or paid fallback), spec-to-plan, per-action approval, scoped AI worker with rollback/checkpoint, browser/build tools and evidence-based final verification. This slice is a local preflight, NOT the end of Phase 5.
