# LYRA Video Studio Slice 18 — Real local Durable Object RPC integration

## Scope: no Cloudflare deployment, no paid services

- CI launches Cloudflare's pinned Wrangler 4.102.0 with **local-only** workerd, bound to `127.0.0.1:8788`. Account and API token environment variables are blank. A disposable Wrangler configuration is generated on the runner and deleted after the probe. The checked-in OAuth staging Wrangler template stays disabled and unchanged.
- The test-only Worker `connector-broker/test/video-runtime-probe.mjs` imports the **real staging Durable Object classes**, not mocked JavaScript adapters, and exposes a loopback-only test RPC path that is NOT referenced by either staging or production config.
- Three separate HTTP round trips validate that the real Durable Object storage persists encrypted server sessions, one-time PKCE state and client rate counters across requests. It tests wrong-browser rejection, successful single consumption, replay rejection, server-session revocation, rate-limit cap under concurrent calls, owner quota replay protection and owner isolation.
- An independent Python localhost harness checks HTTP status, phase correctness and no session/cookie leakage. All 55 inherited Node tests and 28 inherited Python tests still run, plus standard Android build/signature CI.
- The test token, vault key and IDs are **dummy test literals**. No GitHub OAuth App is contacted, and the test worker is never compiled into or deployed as a public endpoint.

## Security boundaries

The test-only entrypoint is **not safe to deploy publicly** and must never be added to the production or staging Wrangler config. It is a temporary CI harness, not a new LYRA public API. The local worker is bound to loopback and requires a test-only request header; that header is NOT authentication. Do not use it outside the isolated GitHub Actions runner.

## Nonclaims and next decision

- No real Cloudflare Worker deployment, OAuth credential provisioning, GitHub Actions write access, automatic render dispatch, 30/60-second video or physical Android tests were performed.
- Local workerd has real Durable Object RPC/storage semantics, but successful results do **not** establish real hosted Cloudflare persistence, alarm execution or browser OAuth consent. Those require explicitly approved staging with reviewed Cloudflare free-tier limits and secret storage.
- Once this exact branch head is GREEN on isolated runtime plus Android APK CI, the next meaningful step is to prepare an owner-controlled real Cloudflare staging rollout **without activating it automatically**. Existing 6-second Blender proof of concept and all production LYRA features remain unchanged.
