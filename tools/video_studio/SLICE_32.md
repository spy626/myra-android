# LYRA Video Studio Slice 32 — authenticated READ-ONLY OpenRouter Wan video preflight

Base: Slice31 final commit `b4aa412a5539b33a8471803b44fe4b1fbfd81a1a`.
Branch: `agent/lyra-video-studio-slice-32`. Draft PR against Slice31. No
main changes or deployment.

## Actual scope
The user confirmed the *existing LYRA Android* OpenRouter integration works.
That does NOT establish that its encrypted API key is accessible to this
GitHub/Cloudflare development tool or to GitHub Actions. Do not extract or
log Android key material.

This slice introduces a separate, strictly **GET-only** authenticated
availability probe. It makes at most two fixed-origin requests:

1. `GET https://openrouter.ai/api/v1/key` — verify the credential
   authentication and read the `is_free_tier` boolean only, never expose
   the key, label, user identity, balances or raw response.
2. `GET https://openrouter.ai/api/v1/videos/models` — check the
   authenticated video model catalogue for exact IDs:
   `alibaba/wan-2.7:free` and `alibaba/wan-2.6:free`.

For each candidate, report one of `NOT_LISTED`,
`DUPLICATE_ID`, `PRICE_UNVERIFIED`, `SETTINGS_UNSUPPORTED`, or
`CATALOGUE_ZERO_PRICE_AND_SUPPORTED` (supports 5 seconds, portrait 9:16,
and approved 480p/720p resolution). Any absent, unknown, paid or mixed
pricing SKU fails closed, and the result must pass the existing Slice31
conservative catalogue validator as defense in depth.

## Safety contract
- No POST, video jobs, video generation, paid model fallback or deployment.
- No video generation key in the repository, APK, CLI arguments or logs.
- No raw response data, API key, provider URLs or billing fields in audit.
- Only server-provisioned `LYRA_OPENROUTER_VIDEO_PREFLIGHT_KEY` may be used,
  and only in the **push** preflight CI job. Pull request jobs never receive
  it. Other jobs (Blender, Android) never receive it.
- Without that secret, the optional CI preflight prints
  `SERVER_KEY_NOT_CONFIGURED`; the workflow may still PASS tests, but the
  live authenticated availability remains **unverified**.
- API key validity, catalogue zero price, and working LLM access are
  separate facts. Even a successful zero-price metadata check cannot
  ensure actual successful video generation or a provider-enforced $0
  spending account constraint.
- Future production integration must use trusted owner OAuth session,
  atomic quota, actual server-side free-spend guarantees and explicit
  submission approval; existing broker remains unchanged.

## Code and acceptance
- `connector-broker/src/video-openrouter-preflight.mjs` — bounded
  account probe, only two literal fixed HTTPS GET targets.
- `connector-broker/scripts/video-wan-readonly-preflight.mjs` — CLI that
  consumes only the environment secret and outputs sanitized JSON.
- `connector-broker/test/video-openrouter-preflight.test.mjs` — adversarial
  tests for missing/revoked credentials, redirects, invalid JSON, malicious
  network error, paid/missing/duplicate/unqualified IDs, and zero POST.
- `.github/workflows/video-studio-slice-32.yml` — separate 5-minute
  GET-only preflight CI job plus full inherited Slice31 30s Blender,
  Python, Node and 32-sample Cycles/FFmpeg regression.
- Normal Android push/PR unit/build/signature jobs still required.

This is an availability **preflight only**. If CI reports
`SERVER_KEY_NOT_CONFIGURED`, there is *no account-specific live check*
and the next action is to connect a dedicated read-only preflight key in
GitHub Actions via authorized GitHub settings (not paste it in chat).
Actual MP4 remains ungenerated.
