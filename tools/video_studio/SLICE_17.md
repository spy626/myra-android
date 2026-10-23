# LYRA Video Studio Slice 17 — Cloudflare Wrangler compile + local runtime smoke

## Verified only when dedicated CI is GREEN

- Run the real Cloudflare Wrangler CLI at an **exact pinned version (4.102.0)** on a GitHub Actions free-hosted runner. `wrangler deploy --dry-run --outdir` compiles the **separate** staging Worker, its Durable Object RPC exports and SQLite bindings without uploading to Cloudflare. This is *not* a real live Cloudflare staging test.
- Run `wrangler dev --local` bound to **127.0.0.1 only**, with no Cloudflare credentials and with both staging enablement flags **false**. The Python smoke test probes the login start, OAuth callback, logout, forbidden dispatch and incidental routes. Each must return 503 `video_auth_not_deployed` and no browser cookies.
- Preflight and all existing Node/Python authorisation regressions stay mandatory, as do the standard Android debug APK build and signature checks.
- Workflow gets **contents: read only**. No `CLOUDFLARE_API_TOKEN` or `CLOUDFLARE_ACCOUNT_ID`, no GitHub Actions write scope, no upload of compiled bundles and no deployment automation. No paid Cloudflare account or plan is required for local-only smoke tests.

## Limitations and dependencies

- This slice **does not** activate OAuth, link the Android app, test a live Cloudflare account, run a real GitHub OAuth exchange, submit GitHub Actions or produce a 30/60-second MP4.
- This is a hardening step beyond offline static mock tests: it exercises Cloudflare's actual compiler and local Workers runtime, but a real deployed Durable Object remains untested.
- Because the staging Worker is intentionally disabled, local smoke does **not** test successful GitHub login, populated vault state, production-rate quotas or real Cloudflare alarms. Later real-account tests require a separately approved HTTPS staging origin, OAuth App registration and server-only secrets, a privacy/quotas review and physical phone acceptance.
- The working 6-second Blender render prototype, live production connector, `main`, Android chat/voice/memory and previous draft PRs remain untouched.

## Manual evidence paths

The GitHub Actions run for this branch should show `staging-bundle-and-loopback` and `build` green for the **same exact commit SHA**. Never call Slice 17 fully GREEN merely because static preflight or Node tests are green; Android APK signature checks and compiled Worker local runtime must also succeed.
