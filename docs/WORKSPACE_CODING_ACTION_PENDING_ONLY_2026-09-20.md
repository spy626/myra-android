# Workspace Chat coding action state — 2026-09-20

Physical-phone recording: after Keep and again after a failed follow-up website request, Chat still displayed `Continue coding request`. The follow-up failed with `Website response must contain only a files object` and the requested Things to Explore cards were not saved. This is not a website-build phone pass.

Fix: The Chat action is selected from actual current persistent records: website backup → `Review website · Undo / Keep`, scoped-file backup → `Review edit · Undo / Keep`, recoverable saved proposal → `Review saved code change`, otherwise no coding action button. Removed the unconditional `Continue coding request` based on the last user message. New explicit coding messages still use the existing automatic Send → Work coding route, not another per-file approval.

Regression tests cover empty state after Keep/Undo, website and scoped pending backups, saved proposals and action priority. Existing project files, rollback mechanisms, provider routing, and preview are unchanged. The separate free-provider JSON-schema failure still needs investigation; an invalid or incomplete model response must not be applied. CI compile/tests are not a physical-phone acceptance test.
