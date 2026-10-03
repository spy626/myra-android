# Groq Free / ZDR test matrix (2026-09-19)

Preconditions: User confirmed Free account and provided a screenshot showing Inference APIs ZDR enabled, Global ZDR disabled. No API key may be committed or pasted into chat. No functional provider integration is asserted by this document.

1. Free account / ZDR opt-in is default OFF; a saved Groq key alone does not enable outbound traffic. Show one-time transparent provider-specific disclosure (recipient, selected-chat text, potential memory only if separately permitted, images/files excluded) and an OFF control.
2. Only selected chat history; latest prompt preserved in full; local outbound cap and model native token budget are separately verified. If uncertain, fail locally with useful error, never slice the latest prompt.
3. Free model allowlist from current Groq docs; reject all old/deprecated model IDs and Compound built-in tools, especially web search and code execution that might incur charges.
4. Full stop/cancel, stale chat changes, deleted chat, duplicate-resend prevention, rate-limit backoff and no paid or unverified cross-provider fallback. No automatic source edits or file transfer.
5. API key taken from existing encrypted `ApiKeyStore.GROQ`; never included in app telemetry, errors, tests, source or external other-provider request.
6. Simulate 401, 403, 429, timeout, incomplete output, malformed JSON, missing key and opt-out; original chat and source remain safe.
7. Green unit tests and assembleDebug are required before release. Real phone test by user is independent, not implied by CI.

Groq rate limits: https://console.groq.com/docs/rate-limits ; privacy: https://console.groq.com/docs/your-data ; Free account/paid upgrades: https://console.groq.com/docs/billing-faqs .