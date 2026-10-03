# Workspace free cross-provider fallback — 2026-09-19

## Scope and source ideas

This is a narrow addition to LYRA's existing native Workspace selection point and OkHttp retry interceptor. No second agent/router, Gemini use, new memory store, source editor, or paid model. The GitHub references below are design references, **not dependencies or copied implementations**:

- [BerriAI/LiteLLM router documentation](https://github.com/BerriAI/litellm/blob/main/docs/my-website/docs/routing.md): bound retries, exclude an unsuccessful deployment, manage provider cooldowns. We use one request-local alternate, not its full runtime.
- [OpenAI Agents Python model-call retry docs](https://github.com/openai/openai-agents-python/blob/main/docs/models/index.md): bounded timeouts and replay safety; never replay after response events or local side effects. LYRA is non-streaming and only switches after explicit HTTP rejection.
- [LiteLLM context-window fallback issue #31557](https://github.com/BerriAI/litellm/issues/31557): an alternate with smaller context can reject the same payload; LYRA keeps the entire prompt and checks the Groq local budget before constructing this single alternate. Exact OpenRouter free-model context remains unverified.
- [OpenRouter provider-routing reference](https://openrouter.ai/docs/guides/routing/provider-selection): request-side provider privacy/price restrictions. The zero-price / ZDR / deny collection / no internal fallback constraints remain explicit.

## Current two-provider topology

1. `WorkspaceFreeProviderSelection` still chooses the initial provider once. Groq is only eligible for approved bounded text; attachments/long text are OpenRouter-only or remain local.
2. `WorkspaceFreeRouteRetry` can retry 429 with a short numeric Retry-After or 502/503/504 once on the *same* provider. No unknown-outcome network retry, 408 retry, 401/402/403 retry, or streaming/partial-output retry.
3. **Only when the separate `workspace_free_cross_provider_opt_in` switch and Groq Free/ZDR opt-in are ON and an eligible encrypted OpenRouter key exists**, a final Groq HTTP 429/502/503/504 rejection can make ONE sequential OpenRouter Free text attempt. No reverse switch under the current eligibility policy; no fallback loops.
4. The original entire selected-chat messages and generated instructions are copied from the already-built Groq request. The OpenRouter request explicitly uses `openrouter/free`, `max_price` zero for prompt/completion/request/image, `zdr=true`, `data_collection=deny`, `allow_fallbacks=false`, and disables context compression. A server may still reject a request; the app must not truncate it silently. Groq has no enforceable API-side $0 ceiling: its Free account status remains a user-confirmed prerequisite.
5. No attachment, project source, voice data, other chat or saved AIRI memory is sent in fallback. The OpenRouter-only saved-memory interceptor runs *before* the retry interceptor, so it does not retroactively enrich this Groq-to-OpenRouter fallback.
6. The existing 35-second total call timeout and Stop cancellation cover retries and fallback. The Activity receives one final callback and only saves a complete reply or leaves its prior reply unchanged.

## Current limitations / next checks

- Rate-limit HTTP 429 is **not proof** of an exhausted daily free quota; there is no live provider quota meter.
- Groq's 12,000-character local guard is not a tokenizer or accurate input/output reservation. OpenRouter's `openrouter/free` may choose a model with another context window; do not promise exact context acceptance.
- On malformed/partial success, token-limit finish, timeout, auth/payment refusal, or missing consent/key, there is no cross-provider retry.
- A newly added provider requires its own explicitly verified free entitlement, real capability/context guard, privacy consent, and response parser before joining this policy. A key alone never enables routing.
- CI lint/unit tests/debug build and **physical Android testing** are separate acceptance gates. No phone pass is claimed by a GitHub Actions green run.
