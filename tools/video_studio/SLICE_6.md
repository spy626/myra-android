# LYRA Video Studio Slice 6 - Authorized manual render job

This feature-branch proof adds a GitHub Actions workflow with an authenticated
manual workflow_dispatch entry point, constrained options, an actual six-second
Blender/FFmpeg render, and a completion receipt tied to the GitHub run ID,
commit SHA, video size and SHA-256 digest. The built-in GitHub Actions run page
is the authoritative live state tracker.

WARNING: GitHub manual workflow_dispatch is normally visible only after a
workflow exists on the default branch. This experimental feature stays on
its own branch to protect the working app. Push/PR CI tests the exact same job
using a fixed bounded smoke input; that is not a phone submission.

The APK must never contain any GitHub PAT, OAuth secret, private repo token,
worker credential, or unguarded dispatch URL. The app currently can export a
reviewed house request, but it CANNOT automatically trigger the job or poll a
private GitHub Actions status. Job output is retrieved through GitHub's own UI
by an authorized signed-in user. Android gets a validated read-only run link.

Only one/two floors, three wall colors, flat/gable roof, garden on/off are
supported. Other words are NOT semantically understood. Video duration remains
six seconds at 360x640 and 12 FPS, even when the future Shorts target is 30
or 60 seconds. Free GitHub Actions usage is finite and subject to quotas.

A SUCCESS receipt is written ONLY after rendered frames, MP4 and geometry
checks pass; failures are reflected by GitHub Actions. No extra app database,
memory brain, website changes or code copied from external repos.

## Slice 6 cancelled-run recovery

A previous render job was cancelled at the 25-minute job limit while downloading
Blender from the runner's default Azure Ubuntu mirror (the 35.9 MB package
alone took roughly 12 minutes). The same exact APT dependency set installed
in seconds on an earlier run. The recovery now probes a small sample from
**Ubuntu archive mirrors only**, picks a responsive archive or fails early,
retains Ubuntu package-signature validation, and keeps the same Blender
renderer, MP4 checks and receipt workflow. It does not bypass failed tests or
mark a cancelled render as passed.
