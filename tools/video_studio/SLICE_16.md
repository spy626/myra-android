# LYRA Video Studio Slice 16 — Offline OAuth End-to-End Smoke Tests

## What is newly verified

- Runs the existing staging Worker and real OAuth vault / rate-limit adapter code with deterministic in-memory Durable Object-compatible storage test doubles; **no new production code, runtime secrets, external network requests, or deployment**.
- Simulates a browser redirect through GitHub OAuth authorization-code / PKCE S256 login, fixed HTTPS callback, fake read:user token exchange, verification of a deterministic fake GitHub user, opaque encrypted server session, and same-origin POST logout.
- Exercises one-time callback replay rejection, wrong-browser state proof, cross-origin logout CSRF protection, missing/disabled staging bindings, rate-limit enforcement before pending-state creation, and selective alarm cleanup of expired auth state while preserving owner reservation data.
- Mocked HTTP exchange is intentionally constrained to two known GitHub API URLs; no actual GitHub account or OAuth app is accessed, and no network access or actual Cloudflare Durable Object emulator is claimed.
- CI runs the new smoke test, then all previous Node security regressions, Python Blender / staging-readiness contracts, and existing unchanged Android build + APK signature workflows.

## Boundaries and next gate

This is a **Node-based simulated runtime integration test**, not a live Cloudflare deployment or real Chrome/Android acceptance. The Slice 15 staging preflight still reports staging_activation=BLOCKED. No GitHub Actions write permission or rendered-video dispatch route is introduced; the 6-second Blender prototype remains unchanged. Before any real staging activation, an explicit owner-approved domain, OAuth App + secrets, Cloudflare free-plan/quotas review and actual Cloudflare/browser tests are needed. Production broker, main branch, LYRA memory/chat/voice/Workspace, and phone app remain untouched.
