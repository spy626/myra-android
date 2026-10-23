# LYRA Video Studio Slice 13 — Durable OAuth Abuse Gate

## Implemented in isolated, disabled staging code

- Added `video-auth-rate-limit.mjs`, a **Cloudflare SQLite Durable Object storage adapter** with atomic 10-minute time buckets for GitHub OAuth **start** and **callback** requests.
- Per-anonymous-client caps: **4 starts** and **8 callback attempts** per 10-minute bucket; shared global caps: **30 starts** and **60 callbacks** per bucket. These deliberately conservative numbers are staging test policy, not a production service guarantee.
- The worker uses the Cloudflare-provided `CF-Connecting-IP` rate **hint** only to derive HMAC-SHA256 keyed by the private `VIDEO_AUTH_RATE_KEY_HEX`; no raw IP is stored in the limiter's Durable Object, and it never serves as authenticated identity. Any missing/invalid source, missing secret, missing DO binding, corrupt quota record or storage failure rejects login/callback before OAuth vault or GitHub calls. Global caps limit source rotation.
- Added Cloudflare Durable Object RPC export and storage **alarm handler** to delete stale per-client / global buckets. No `deleteAll` or cleanup of the separate owner reservation ledger.
- Added `VIDEO_AUTH_RATE_GATE` binding and separate `video-auth-staging-v2` SQLite migration **only** to staging Wrangler example; flags stay disabled and no deployment occurs.
- Regression tests cover per-client/global ceilings, concurrency, persistence across wrapper recreation, expiry cleanup, corrupt state, missing client source, unavailable gate, and no raw IP storage. Existing login, logout, owner reservation, Android/Python fingerprint and authorization tests must remain green.

## Unfinished — NO automatic deployment

- Public staging domain, GitHub OAuth App, secrets, per-request Cloudflare identity assumptions, rate-limit threshold tuning, real Durable Object alarms, and Cloudflare free-tier usage must still be verified in owner-approved staging.
- **OAuth pending-state and session expiry garbage collection is not implemented in this slice**. That is a separate narrow task, not a permission to delete active render reservations.
- Current live connector routes, credentials, LYRA Android chat/voice/memory/Workspace, and Blender render pipeline remain unchanged.
- Still no GitHub Actions write grant, automatic render dispatch, 30/60-second completed MP4, authenticated video delivery or physical-phone acceptance.
