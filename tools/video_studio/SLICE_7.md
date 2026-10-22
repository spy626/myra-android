# LYRA Video Studio — Slice 7: Real read-only job status + approved manual handoff

## Why Slice 7 uses a public GitHub read

The LYRA repository \`spy626/myra-android\` is public as of this change, and
official GitHub Actions run details are publicly readable. A **manual tap**
on "Check real GitHub status" now performs one bounded HTTPS GET against
\`https://api.github.com/repos/spy626/myra-android/actions/runs/{numericRunId}\`.

The status reader:

- Uses a fixed GitHub HTTPS host and fixed repository; run IDs are numeric,
  1–18 digits, nonzero; URL redirect following is disabled.
- Sends **no GitHub token, OAuth credential or private LYRA conversation**.
- Applies timeouts and a strict 64 KiB response bound. On 403/429 rate limits,
  404/unavailable runs, offline errors, malformed JSON, or identity mismatch,
  the app shows an **unverified** result and links to official GitHub.
- Checks the exact workflow name and path, both repository identities, official
  run URL, allowed branch, event, 40-hex commit SHA, attempt count and
  state/conclusion consistency. An unrelated green Android build can never
  masquerade as a successful video-render job.
- Makes **no auto-polling or automatic notification**. The status is the result
  of one user-requested API read only; the original GitHub Actions run page
  remains the authoritative source.
- Clearly distinguishes a GitHub workflow success from visual quality,
  real phone verification or a 30/60s video.

## Render submission: still intentionally manual

The "Review manual render options on GitHub" control validates the 6-second
mini-house request (bounded floors, colors, roof, garden) and shows the actual
parsed values. If the user elects to continue, it opens the repository's
GitHub Actions workflow page. **Opening a link is not submitting a job.**

An authorized GitHub owner must approve a deployment to the repository's
default branch before GitHub's standard \`workflow_dispatch\` UI can be used.
That deployment is deliberately **not** part of this slice to preserve
\`main\`. No Android PAT, GitHub App token or broker write route is introduced.
This project already has a GitHub connector, but reusing its write privileges
for unrestricted Actions dispatch would need an independent permission,
scope, rate-limit and anti-CSRF review.

## Evidence gates

- Android JVM unit tests exercise accepted/forged run metadata, invalid IDs,
  pending/failed/success states and safe response parsing.
- GitHub Actions Android CI must pass before this slice is accepted.
- The 6-second Blender renderer was left **unchanged** from the Slice 6 green
  commit; Slice 7's verified status can check those actual past run IDs.
- No physical Android phone test has been claimed and 30s/60s Shorts,
  in-app MP4 download or automatic cloud render submission are NOT available.

No changes to LYRA memory architecture, Workspace, voice or main branch.
