# Workspace free-provider audit — 2026-09-19

This is an implementation register, not a claim that every provider or the user's account has passed a live-phone test. Consult `WORKSPACE_IDEA_LEDGER.md` for all collected candidates. The code uses one Workspace gateway and the existing LYRA Core, memory owner, Safe Edit and Voice pipeline.

## Integrated first batch

- OpenRouter `openrouter/free` remains the default when Groq Free is not explicitly enabled. Its request body carries an API-side `max_price` zero guard for prompt, completion, request and image alongside `zdr=true`, `data_collection=deny`, `allow_fallbacks=false` and disabled context compression. This only controls eligible price; it does not guarantee endpoint health.
- Groq Free text-only adapter: pinned `openai/gpt-oss-120b` model, Free account and Inference APIs ZDR confirmed by the user (not independently retrievable through a key); **default OFF** until the user explicitly enables the Free+ZDR checkbox in API & Cloud Settings. Short text chat uses Groq after opt-in. If the latest prompt exceeds a conservative local length threshold and an OpenRouter Free key is configured, the already-configured hard-$0 OpenRouter route is chosen; actual historical context can still exceed Groq's local budget and fail explicitly. No fallback to a different provider after a request fails, no retry on ambiguous timeout, no Gemini Voice key, no remote browser/code tools and no automatic project-source edits.
- Groq never receives saved personal memory from the OpenRouter-only memory interceptor. Photos and files are blocked before sending while the opted-in Groq text route is active. The latest full prompt is never sliced: Groq rejects input over its local 12000-character outbound-message budget. Groq's 131072-token native context does **not** imply a 131K-token free quota; currently published Free limits for GPT-OSS 120B include 8K tokens/minute, which may still cause rate limits.
- **Billing limitation:** Groq has no API-side hard $0 price ceiling. On the user's confirmed Free tier, free quota exhaustion is rate-limited; if they later upgrade Groq to a billed Developer account they MUST disable Groq in LYRA first. The app cannot programmatically verify their account tier from the saved key or promise that future billable-tier requests cost $0. Neither a saved key nor the presence of a provider in the ledger activates it.
- The one-time exact Workspace patch passed unit tests and debug APK compilation in CI. Final signed build and **physical-phone acceptance remain separate**; do not call this feature phone-passed before the user tests it.

## Provider audit status

| Candidate | Evidence and constraints | Disposition |
|---|---|---|
| OpenRouter `openrouter/free` | Random available free model, advertised router-level 200K context; actual underlying model and health vary. https://openrouter.ai/openrouter/free/ | INTEGRATED, hard $0 price eligibility guard; live phone behavior varies. |
| Groq Free `openai/gpt-oss-120b` | User Free+ZDR confirmation; published 8K tokens/minute Free quota, 131K native window; API pricing can apply after account upgrade. https://console.groq.com/docs/rate-limits ; https://console.groq.com/docs/billing-faqs ; https://console.groq.com/docs/your-data | INTEGRATED **opt-in text only** with billing caveat, no account-tier API guarantee; phone test pending. |
| OpenRouter NVIDIA Nemotron 3 Ultra `:free` | Free-endpoint page warns of prompt logging for security/product improvement. https://openrouter.ai/nvidia/nemotron-3-ultra-550b-a55b-20260604%3Afree | EXCLUDED from personal chat/memory auto-routing under current privacy requirements. |
| Other collected providers/routers | Account-tier pricing, privacy, terms, context and live availability remain unverified. | REVERIFY individually; not enabled. |

## Next gates

1. Phone-test updating the **existing installed APK** without uninstalling. Workspace chats live under `noBackupFilesDir`; uninstall can irreversibly erase local conversations/keys. Compare release signing fingerprints if Android rejects an update. No guarantee of data recovery without verified backup/export.
2. Test Groq switch OFF, missing key, enabled Free+ZDR, simple Hinglish chat, same-chat follow-up, long prompt, rate-limit banner/Retry, Stop and photos/files denied. Ensure Groq key and personal memories never leak to another provider. Confirm no project source or unknown tool use, and voice still works.
3. Add further providers only after validating exact recurring $0 entitlement, account billing lock, model limits, privacy, quota and real-phone acceptance. Design shared capability-aware selection, but don't equate local character count with exact tokens or silently switch across privacy boundaries.
