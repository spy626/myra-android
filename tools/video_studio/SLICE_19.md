# LYRA Video Studio Slice 19 — owner-gated staging release evidence

## What Slice 19 delivers (no deployment)

- A **read-only release evidence generator**, `tools/video_studio/staging_release_evidence.py`, audits the existing disabled OAuth staging template and inspects exactly one JavaScript output from pinned Wrangler 4.102.0 `deploy --dry-run`. It records only commit SHA, template + bundle SHA-256 digests, bundle byte count, expected Durable Object export names and explicitly false readiness/deployment flags.
- `test_staging_release_evidence.py` contains **10 negative and positive checks** for disabled flags, invalid git commit, missing/multiple/tiny/oversized/symlinked or altered bundle and staging preflight failure. All previous OAuth/owner/Blender tests remain required.
- GitHub Actions builds the separate Worker into an ephemeral runner directory with blank Cloudflare credentials, produces only a short JSON evidence file and uploads **metadata only** as `lyra-video-slice19-offline-release-evidence`. Bundled JavaScript, OAuth tokens, secrets and third-party service credentials are never artifacted.
- No online Cloudflare account, billing action, GitHub write permission, OAuth activation, live staging or video dispatch is performed. The evidence file is not cryptographically signed and does **not** claim deployed Cloudflare validation.

## Evidence semantics

The generated JSON MUST retain:
- `mode: "offline_wrangler_dry_run_only"`
- `staging_activation: "BLOCKED"`
- `deployed: false`, `workflow_dispatched: false`
- `hosted_cloudflare_validated: false`
- `ready_for_live_activation: false`
- `manual_owner_approval_required: true`

A security check or APK build can be GREEN while activation remains **BLOCKED**. No external Cloudflare staging is connected.

## Approval checklist — not automatically executable

1. Approve isolated HTTPS origin and domain ownership, separate from production LYRA Worker; register its exact callback in a GitHub OAuth App under a controlled test account.
2. Review current Cloudflare Workers/Durable Objects **free-plan** billing and quotas in the actual account; do not assume a free-tier entitlement or enable paid usage.
3. Provision client ID, client secret and independent random AES/HMAC keys only in Cloudflare's protected secrets; never commit, log or publish real credentials.
4. Define TLS, redirect allow-list, bounded login/callback budgets, incident-response/revocation controls, retention and access logs before any live attempt.
5. Obtain explicit owner authorization for deployment and manual approval before changing either staging feature flag; do not point it at production domain.
6. After approval, test real hosted Durable Object RPC/alarm retries, browser logout/consent, GitHub `/user` verification, token expiry, and quota behavior; then perform physical Android acceptance.
7. Keep GitHub Actions dispatch and authenticated video delivery disabled until they have separate, narrowly reviewed capability design and consent.

## Never claim

- Actual hosted Cloudflare Worker, real GitHub OAuth login, 30/60-second MP4 generation, user-facing Android Video Studio, phone testing, or deployment approval.
- Production `main` changes, automatic video rendering or Cloudflare billing. Existing deterministic **6-second** Blender prototype is unchanged.

## Verification

The exact final Slice 19 commit requires successful **offline release evidence CI** and **Android unit tests, debug APK build, v1/v2/v3 signature verification and artifact upload**. Draft PR targets Slice 18, not main.
