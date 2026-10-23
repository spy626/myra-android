# LYRA Video Studio Slice 14 — Durable OAuth expiry cleanup

## Implemented, not deployed

- Existing encrypted OAuth vault now uses a Cloudflare Durable Object alarm for pending GitHub OAuth states (up to 10 minutes) and server-only encrypted sessions (up to 30 minutes). The alarm is scheduled before records are inserted; broken scheduling fails closed.
- The staging-only Cloudflare `VideoOAuthVault` entrypoint implements `alarm()`, which invokes the vault's bounded-scope `cleanupExpired` with platform time.
- Cleanup enumerates only hashed `oauth:pending:` and `oauth:session:` keys and rechecks expiry transactionally before deleting. Active records survive, and the next expiry is rescheduled. Repeated invocations are idempotent; original expiry and single-use checks remain.
- No `deleteAll`; the separate render owner reservations, daily quotas, nonces and rate-limit ledger remain untouched.
- Tests cover selective expiry, encryption secrecy, malformed-record failure, alarm outage, already-revoked/consumed records, and preserving old OAuth/Blender compatibility.
- No new public routes, GitHub Actions permission, Android feature or production credential. The existing Cloudflare broker remains unchanged.

## Honest limits

No Cloudflare staging instance is deployed or physically tested, and alarm execution is at-least-once, potentially delayed and limited in retries. An expired session remains invalid even before cleanup runs. Cleanup only revokes local session records and does not revoke GitHub OAuth access grants. A consumed OAuth state can leave an unnecessary future alarm, which remains safe and idempotent. This is not automatic video dispatch; Blender remains a verified 6-second prototype.
