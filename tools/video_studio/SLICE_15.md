# LYRA Video Studio Slice 15 — Offline Staging Readiness Preflight

## Implemented, no live deployment

- New check_staging_readiness.py audits the staging Wrangler example with Python 3.11+ tomllib (stdlib only); it never calls Cloudflare, GitHub, external sites or a deployment API.
- The fail-closed audit requires: an exact separate staging Worker entrypoint; workers_dev=false; both feature flags exactly "false"; no unreviewed routes or exposed assets; no hard-coded OAuth or encryption secrets; exact three Durable Object bindings; append-only expected SQLite migration order; and presence of the original staging RPC wrappers, OAuth guard and cleanup alarm.
- Output is machine-readable JSON using diagnostic codes only, never printing user-supplied configuration values or secrets. A passing static contract is not an approval to deploy: every report explicitly states staging_activation: BLOCKED, deployed: false, workflow_dispatched: false, and lists external prerequisites.
- Negative tests prove that enabled flags, embedded secrets, misdirected broker entrypoints, unexpected public routes, missing bindings, migrations and missing runtime protections cannot be marked static-ready.
- Separate Slice 15 CI reruns all security Node and Python regression tests. Standard Android CI still builds and verifies the unchanged APK.
- No new Cloudflare bindings, secrets, OAuth client ID, token grants, main branch changes, mobile screens or render workflows.

## Owner-controlled operational blockers (still unresolved)

1. Explicit approval for an isolated HTTPS staging domain and a registered GitHub OAuth callback.
2. Server-only OAuth App credentials and cryptographic secret provisioning.
3. Review Cloudflare free-tier quota/billing, rate limits, logging and incident controls before any activation.
4. Perform real Cloudflare Durable Object, alarm and browser acceptance tests in an authorized staging instance.
5. Validate authentication UX and eventual Android App Links on a real phone.
6. GitHub Actions write authorization, job dispatch and owner-authenticated video downloads are future designs, not enabled by this slice.

## Known nonclaims

Static preflight is not a real Cloudflare runtime integration test. No staging Worker is deployed; no owner OAuth flow has been completed; no 30/60-second video is rendered; no real Android acceptance has passed. The deterministic 6-second Blender prototype is unchanged. Do not merge this draft PR to main.
