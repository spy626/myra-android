# Workspace free-provider audit — 2026-09-19

This is an implementation gate, not a claim that accounts or live routes have passed a phone test. Consult `WORKSPACE_IDEA_LEDGER.md` for the complete collected candidate list. The code continues to use one Workspace gateway and the existing LYRA Core, memory owner, Safe Edit and Voice pipeline.

## Shipped foundation

- Existing OpenRouter `openrouter/free` route only, not a completed multi-provider router. OpenRouter describes this as a randomly selected free model and advertises a 200K-token router context, which is not a guarantee of a particular underlying endpoint's availability.
- Every Workspace Chat request now also sends provider `max_price` zero for prompt, completion, request and image, alongside existing `zdr=true`, `data_collection=deny`, `allow_fallbacks=false` and disabled context compression. Zero price is an API-side eligibility guard, not a claim about live endpoint health. If none qualify, fail closed; preserve the local message, no paid fallback.
- Retain separate explicit consent for sharing saved LYRA personal memories. Never auto-export project source or attachments to a second provider. Do not conflate multiple models hosted by one aggregator with independent provider accounts.

## Researched candidates, not yet wired

| Candidate | Current published evidence | Gate |
|---|---|---|
| OpenRouter `openrouter/free` | $0 router; selects among current free variants; advertises 200K context. https://openrouter.ai/openrouter/free/ | INTEGRATED, $0 price ceiling added. The actual chosen endpoint and completion quality still require phone tests. |
| Groq free account | Free-tier limits list GPT-OSS 120B / 20B at 8K tokens per minute; GPT-OSS 120B model advertises a 131,072-token context. The same model can be usage-billed after account upgrade. Groq's inference data retention can be controlled in Console; Zero Data Retention is opt-in. https://console.groq.com/docs/rate-limits ; https://console.groq.com/docs/models ; https://console.groq.com/docs/billing-faqs ; https://console.groq.com/docs/your-data | DEFER direct auto-routing until account is confirmed on Free tier, applicable privacy controls are verified, and a payment-safe hard stop is demonstrated. A saved API key alone does not certify $0. |
| OpenRouter NVIDIA Nemotron 3 Ultra `:free` | Model page advertises $0 and 1M context, but also explicitly warns that free-endpoint requests are logged for security and product improvement, advises against personal/confidential inputs. https://openrouter.ai/nvidia/nemotron-3-ultra-550b-a55b-20260604%3Afree | EXCLUDE from automatic personal chat/memory routing under current ZDR/privacy requirements. Do not use the paid non-`:free` variant. |
| Other collected providers / routers | Ledger candidates have not yet passed account-specific pricing, entitlement, privacy, terms, model-context and availability checks. | REVERIFY individually; do not silently enable or advertise as integrated. |

## Next integration requirements

1. For each exact route, confirm **both input and output price are $0**, context and reply limits, account tier and hard billing stop, live accessibility, retention/training terms, and attachment/memory eligibility. A large published model context does not raise the account's token-per-minute quota.
2. Build one shared, testable provider registry/router using the existing Workspace gateway; do not add a parallel memory brain/router, paid fallback, or Gemini Voice route. The app must never choose an unaudited route, silently truncate latest user text, or send project source/personal memory across an unapproved privacy boundary.
3. Test length-dependent model selection, follow-up context, failed/ambiguous timeouts (no blind duplicate), 429/backoff, Stop, no duplicate saved messages, no paid request, and real-phone Android acceptance. Record exact eligible endpoint IDs and audit date before enabling them.
