# LYRA Video Studio — Slice 12: Durable browser logout contract

## Isolated implementation (not deployed)

- The disabled-by-default staging OAuth Worker now accepts exactly **POST /oauth/github/logout**, without query parameters or body. Existing start/callback behavior remains unchanged.
- Logout requires the configured HTTPS host, an exact browser **Origin** matching that host, and if supplied **Sec-Fetch-Site: same-origin**. Cross-origin forms, GET logout, missing Origin, duplicate/invalid session cookies, and unknown routes fail closed.
- The server reads only an opaque `__Host-lyra_video_session` cookie and calls the existing durable `VideoOAuthVault.revoke`. It clears session and pending `__Host-` cookies only **after** the server-side delete succeeds. The response never contains a GitHub token, ID or session handle.
- Node tests cover authenticated browser session revocation, repeated logout, cross-origin/logout CSRF attempts, invalid cookies, disabled staging, no dispatch route, and storage-revocation failures. Existing Slice 9–11 Node and Python contract tests run unchanged.
- No Android app, production connector, voice, chat, memory, Blender, Actions permission, or GitHub configuration is altered.

## Explicit limitations / next prerequisites

- Local session revocation **does not revoke GitHub's underlying OAuth grant**. GitHub-side authorization revocation and a user-facing consent/logout interface require a separate, reviewed design.
- Separate Cloudflare staging Worker remains **disabled and undeployed**. No real OAuth credentials, real Durable Object instance, GitHub Actions write permission, automatic render dispatch, video download, or physical phone acceptance is claimed.
- Before owner-approved staging activation: deployment configuration, per-client and overall abuse/rate limiting, pending-state cleanup/alarms, durable storage lifecycle checks, and real browser security testing are still required.
- Current Blender output remains a deterministic **6-second** prototype. Neither a 30s nor a 60s completed MP4 is implemented by this slice.
