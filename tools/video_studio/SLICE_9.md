# LYRA Video Studio — Slice 9: Server-Side Render Authorization Policy

This slice creates **only an isolated, tested authorization policy module** in
\`connector-broker/src/video-render-authorization.mjs\`. It does **not** modify
the existing Cloudflare Worker request router, deploy an endpoint, request new
GitHub permissions, change \`main\`, or submit any video-render job.

The existing LYRA GitHub connector's Cloudflare worker authorizes limited
**commit/PR** operations. Its GitHub App Actions permission is **read-only**.
That authority is *not* expanded or reinterpreted. No client API key, GitHub
PAT, installation write token, private-key material or pairing secret is
embedded in LYRA or moved into the video renderer.

## Verified contract

The module requires a *server-derived authenticated GitHub OAuth identity*
and unique server session, plus explicit approval for precisely six supported
mini-house fields. It creates a short-lived (5-minute) **HMAC-SHA256 signed
approval ticket** bound to the owner, session, specific repo, workflow, branch,
and the Slice 8 request fingerprint. Approval tickets carry **no Github API
write permission** themselves.

Before any future dispatch, authorization **fails closed** unless all hold:

- The same authenticated GitHub user and session present the ticket.
- The signature is valid; approved fields and workflow binding are intact.
- The ticket is within its bounded lifetime and unused.
- An independent server-side check verifies approved deployment to the
  repository's default branch and a scoped GitHub App **Actions:write**
  permission on the exact authorized repository.
- A trusted durable transactional reservation store **atomically** enforces
  nonce single-use, one active render per user and at most three submissions
  per user per UTC day. The test-only fake is NOT for production.

Successful validation returns **RESERVED_NOT_DISPATCHED**, not a run ID.
The next slice/service integration must securely implement the missing
OAuth identity provider, signed-in user's consent binding, persistent atomic
quota store, rate limits, Actions permission check, workflow deployment review,
GitHub dispatch, recovery of failed reservations, and authenticated owner-only
job result retrieval.

**Critical:** Values such as \`verifiedDeployment\`, \`verifiedActionsPermission\`
and the server principal are trusted **only when provided by authenticated
server code after independent verification**; they must never be passed
through from an Android request. There is no network route enabling clients
to supply these values in this slice.

### Testing

Node's built-in test runner checks tampering, cross-account/session use,
expiry, replay, concurrency, quotas, overbroad prompts, and exact repository
binding. Node and the existing Python tests validate the same Slice 8 golden
fingerprint. Standard Android CI runs unchanged app tests/build/signing.

### Product limitations

LYRA still has **manual GitHub handoff**; no automatic video submission,
per-user cloud processing, authenticated download, unlimited free compute,
30/60-second generated video, new 3D characters, or real phone acceptance.
No code copied from reviewed third-party production repositories.
