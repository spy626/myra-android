# LYRA Video Studio Slice 11 — GitHub OAuth PKCE and encrypted Durable Object staging integration

## Implemented (isolated staging code, NOT deployed)

- video-oauth-flow.mjs implements GitHub OAuth browser authorization-code flow with PKCE S256, random one-time state, code verifier, and read:user scope. Exact HTTPS callback; no phone-provided redirect. Code is exchanged server-side; GitHub /user is verified before session issuance.
- video-oauth-vault.mjs exports the VideoOAuthVault Durable Object. Browser-bound pending states last no longer than ten minutes, are single-use, and the wrong browser cannot consume them. The GitHub token is AES-256-GCM encrypted at rest with a server-held VIDEO_SESSION_KEY_HEX. Opaque session handles are only stored as hashed keys. Session lifetime at most 30 minutes; explicit revoke and Slice 10 load(sessionId) compatibility.
- video-owner-durable-object.mjs wraps the prior VideoOwnerLedger with a separate DO class export. Its RPC methods are server-internal, not public endpoints; the future broker must choose the owner namespace using a server-verified GitHub identity.
- video-auth-worker.mjs is a separate Cloudflare Worker entrypoint exposing only GET /oauth/github/start and GET /oauth/github/callback. Disabled unless both server deployment flags are enabled. No render dispatch, session introspection or MP4 download routes. Fixed HTTPS origin with Secure, HttpOnly, SameSite=Lax __Host- cookies.
- wrangler.video-auth.example.toml is an unconfigured template with workers_dev=false, both feature flags disabled, and two new_sqlite_classes Durable Object bindings. Existing deployed broker still uses src/index.js and wrangler.toml, unchanged.
- Dedicated Node/Python CI tests PKCE, encrypted sessions, replay/expiry, forged browser state, owner isolation, strict route boundaries, existing Slice 9 authorization and prior Kotlin/Python fingerprint compatibility.

## Deployment prerequisites — NOT DONE

1. Obtain explicit authorization for separate staging domain, GitHub OAuth App callback and Cloudflare deployment. Register a fixed HTTPS /oauth/github/callback. Keep VIDEO_GITHUB_CLIENT_SECRET and VIDEO_SESSION_KEY_HEX exclusively in Cloudflare secrets. This PR does not deploy anything.
2. Add public endpoint abuse/rate limiting, expired-session cleanup/alarms, logged-in browser consent/logout UX, and confirm free plan quota/billing before switching on staging flags.
3. Android App Links / browser-to-app linking is NOT implemented. OAuth currently targets browser-only staging.
4. GitHub Actions:write, owner-authorized default-branch workflow installation, a narrowly scoped dispatch broker and owner-only artifact download are NOT implemented. The current GitHub App permission is read-only for Actions.
5. No live OAuth session, running production Durable Object, automatically submitted video, 30/60-second completed video, or phone acceptance is claimed.

## Security constraints

GitHub supports and recommends PKCE S256. Cloudflare recommends SQLite-backed Durable Objects via new_sqlite_classes. No OAuth token, GitHub App key or signing secret belongs in the APK. The current LYRA Cloudflare broker, main, memory, voice, chat, Workspace and existing Blender renderer remain untouched.
