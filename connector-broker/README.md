# LYRA GitHub Connector Broker

This tiny Cloudflare Worker exists only to keep the GitHub App client secret off the Android APK.

## Browser connect flow

1. LYRA generates a random OAuth state plus a PKCE verifier/challenge locally.
2. LYRA opens `/github/connect?state=...&code_challenge=...`.
3. The broker sends the user to the official GitHub App installation page.
4. GitHub returns to `/github/installed`; the broker starts GitHub OAuth with the same state and PKCE challenge.
5. GitHub returns to `/github/callback`; the broker validates the signed short-lived browser session and redirects to `lyra://github/callback`.
6. LYRA verifies the state and sends the one-time code + PKCE verifier to `/github/exchange`.
7. The broker adds the GitHub App client secret server-side, performs the token exchange, and returns the short-lived user token to LYRA.
8. LYRA stores GitHub tokens only in Android encrypted storage. Tokens are never inserted into model prompts.

The broker does not store GitHub user tokens, repository files, chats, or model content.

## GitHub App settings

Use a GitHub App rather than a PAT or broad OAuth App.

For C1 read-only permissions:
- Repository contents: Read-only
- Actions: Read-only
- Metadata: Read-only (GitHub grants metadata access)
- No webhooks are required in C1

Post-install setup URL:
`https://YOUR-WORKER.workers.dev/github/installed`

OAuth callback URL:
`https://YOUR-WORKER.workers.dev/github/callback`

Do not enable "Request user authorization during installation"; the broker deliberately starts OAuth after the repository installation step so PKCE and state are supplied explicitly.

Keep expiring user access tokens enabled.

## Worker configuration

Public vars in `wrangler.toml`:
- `GITHUB_CLIENT_ID`
- `GITHUB_APP_SLUG`

Secrets:
- `GITHUB_CLIENT_SECRET`
- `OAUTH_SESSION_SECRET` (a random high-entropy value)

Never commit either secret.

## Endpoints

- `GET /health`
- `GET /github/connect`
- `GET /github/installed`
- `GET /github/callback`
- `POST /github/exchange`
- `POST /github/refresh`

All token responses use `Cache-Control: no-store`. Browser session cookies are Secure, HttpOnly, SameSite=Lax, and expire after 15 minutes.

## Deployment

Cloudflare Workers Builds deploys this broker from `agent/myra-phase-1` with `/connector-broker/` as the root directory.
