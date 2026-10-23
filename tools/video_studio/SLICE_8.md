# LYRA Video Studio Slice 8 — explicit, reviewable submission preparation

**What is implemented:** The Android screen can now turn a checked
mini-house draft into a **six-field, bounded GitHub workflow handoff**.
After the user reviews all six values, an explicit action can copy only:

- project_id — safe project ID, not the user's original free-text prompt
- floors — 1 or 2
- color — cream / blue / terracotta
- roof — flat / gable
- garden — yes / no
- target — planned Short length 30 / 60s (NOT the actual render length)

The workflow remains six seconds and low-detail, as before. The user can
also open GitHub's official workflow page for **authenticated manual
submission** after independent owner approval and default-branch installation.
Neither tapping Copy nor tapping Open GitHub starts a video job.

A stable SHA-256 digest of those six manual inputs is printed on the
approval screen. Python's already-existing Blender completion receipt now
includes \`approved_input_fingerprint\` after all prior scene/MP4 validation
checks. Kotlin and Python assert the **same fixed golden fingerprint**.
This fingerprint is a comparison identifier, **not a cryptographic
signature or user-authentication mechanism**. Actual security comes from
GitHub authorization for workflow execution, restricted workflow inputs,
and the existing post-render checks. No model chat, access token, GitHub
pairing secret or arbitrary input script is copied.

**Why this is still manual:** Existing LYRA's Cloudflare GitHub connector
exposes approved feature-branch commit/PR broker operations, **not an
approved GitHub workflow_dispatch endpoint**. Reusing its broad write
privilege as an unreviewed dispatch shortcut would change the security
boundary. No new backend was deployed or assumed. \`workflow_dispatch\`
normally also requires the workflow installed on the default branch;
\`main\` is intentionally unchanged. The repo is public, but writing
workflow runs always requires owner authorization.

**Verification:** Android CI runs all Kotlin tests and APK build/signing.
A separate fast Python workflow tests the source/receipt cross-language
fingerprint, validation failures and prior request schema. The 3D renderer
itself was not changed; successful Slice 6 rendered MP4 tests remain the
reference, not a claim of a newly rendered video in Slice 8.

**Future work:** A separately authorized, scoped broker dispatch endpoint
with opt-in user consent, rate limiting, one-job-at-a-time enforcement,
idempotency/expiry and authenticated run ownership; later a bounded artifact
download/verification path. Do not put a PAT in the app or claim unlimited
free cloud rendering. Physical device validation not yet claimed.

No modification to LYRA memory, voice, chat, Workspace, main or production.
