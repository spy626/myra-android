# LYRA Work — phone-verified recovery checkpoint (2026-09-20)

**Immutable reference commit:** `74f603cd2f19bf9435e20020486d9f22d061474f` (release `airi-memory-74f603cd2f19`). This is a reference point, **not** a claim that every feature or every future output passed.

User-provided Android recording: `Screen_Recording_20260920_223801.mp4`. Observed in the recording: a requested Minicoy website generated and its `index.html`, `style.css`, `script.js` were shown in the project; the saved-site Preview opened; Beaches, Lighthouse and Local Food cards were visible; tapping Explore Minicoy navigated to the requested section; Work Chat showed completion and Undo/Keep. This was physical-phone evidence supplied by the user, not merely CI success.

**Known visual issues (not accepted):** the requested blue hero appeared teal; the section heading and cards misaligned, with narrow cards and excessive whitespace; `Exploring Minicoy!` was already visible before the CTA and appeared again as target feedback. No claim was made that every button or all visual details passed.

Preserve this commit/release as a recoverable checkpoint. Future edits stay on `agent/myra-phase-1`, never force-push or modify `main`, retain ancestor `c307357b53ec4ad3a005769d55f29bc44d74ff41`, and preserve successful provider routing, source safety, actual three-file saving, Preview, Chat completion and Undo/Keep. Visual refinements require their own regression tests, CI and **new** phone acceptance; never relabel this baseline as a design PASS.
