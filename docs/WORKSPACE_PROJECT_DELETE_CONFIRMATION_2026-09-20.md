# Workspace project deletion confirmation — 2026-09-20

Scope: `agent/myra-phase-1` only. Based on phone screenshot and recording of a long auto-generated project name, an OpenRouter Free HTTP 429, and the original exact-name Delete Project dialog.

## Applied

- Delete Project in the Workspace drawer no longer requires typing its often very long project name. A separate destructive Cancel / Delete Project confirmation still describes irreversible removal of project source, task briefs, drafts, rollback data and chat history. Delete Chat remains distinct and preserves project files.
- Before deletion, the project identity, name and type are revalidated. A selected project's in-flight chat and coding requests are cancelled before its source is deleted. All existing project-scoped and symlink guards remain in place.
- OpenRouter HTTP 429 now advises respecting a numeric Retry-After suggestion or trying later rather than repeatedly tapping Retry. A 429 does not establish daily quota exhaustion, and the app does not switch project source to paid providers. Duplicated no-paid-fallback text in coding failure summaries is removed.

## Verified and not verified

The one-time workflow `35471826239` passed the exact-source patch, `:app:testDebugUnitTest`, and `:app:assembleDebug` before fast-forwarding commit `c74265062c2ded037886f08f9f0b6b6ce5504cee` on the phase branch. A physical-phone pass of this new UI is **not** claimed. Provider HTTP 429 remains upstream rate limiting; no code-only fix guarantees service availability. The user did not ask to delete any actual project from the repository or their device, and no local project was deleted by this change.
