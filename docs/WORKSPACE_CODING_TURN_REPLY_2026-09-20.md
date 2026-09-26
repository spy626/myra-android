# Workspace coding turn replies — 2026-09-20

Observed on a physical Android phone: the Minicoy website update generated Beaches, Lighthouse and Local Food cards in the saved project, but the Chat transcript showed only user instructions. A prior request timed out and its failure was not retained as a chat response. Root cause: the coding flow used temporary status text, not an assistant transcript message.

Scoped fix on `agent/myra-phase-1`: coding results are tied to the exact saved user message; after verified file writes, Chat records changed file names and headings extracted from generated HTML, plus an explicit reminder to verify the visual preview. Timeouts/errors record a distinct failure message and can be retried as a new user turn without deleting the earlier failure. Stale results are blocked, and regular Chat cannot accept coding outcomes. Unit tests cover changed/unchanged paths, failures, same-turn retry, and stale-turn rejection.

Do not claim physical phone acceptance from CI. Initial user reports and existing project files are not retroactively rewritten; this change applies to new coding attempts. Existing free provider, safe Undo/Keep, project scope, memory and Voice routing are unchanged.
