# Work website generation: captured Android failure and narrow correction (2026-09-20)

## Phone evidence (user-provided 03:09 screen recording)

The first website request for a Minicoy page returned a completed answer that was NOT a complete `files` JSON envelope, so the safe parser refused to change any file. The user resent the same instruction; an OpenRouter HTTP 429 triggered the separately consented website Groq fallback, but Groq replied HTTP 400 repeatedly. Sending `Hii` to ordinary Chat succeeded and eventually resending the website task also succeeded. A later website iteration succeeded. Successful Chat text and intermittent website success do not prove this website route is reliable.

## Verified request-contract defect

`WorkspaceWebsiteGroqFallback` sent `reasoning_format: hidden` for Groq `openai/gpt-oss-120b`. The model-specific Groq reasoning docs say GPT-OSS does not support `reasoning_format` and instead supports `include_reasoning: false`. The filmed HTTP 400 body is deliberately not exposed or logged, so the exact upstream error message is unavailable; the unsupported field is a confirmed incompatible request parameter, not an independently proven unique cause of every 400.

References: https://console.groq.com/docs/reasoning and https://console.groq.com/docs/structured-outputs and https://openrouter.ai/docs/guides/features/structured-outputs .

## Narrow correction

- Remove the unsupported GPT-OSS `reasoning_format`; use `include_reasoning: false`, retain `reasoning_effort: low`.
- On the separately consented Groq fallback, require strict structured JSON with exactly `files` containing string values for `index.html`, `style.css`, `script.js`; the existing local parser, secret scan, snapshot freshness and durable Undo remain authoritative.
- On OpenRouter Free primary, request `json_object` and `require_parameters: true` to avoid silently routing to endpoints that ignore the format. The existing `max_price: 0`, ZDR, no collection, provider-fallback disabled and no memory/file attachment injection remain intact. If no compatible free endpoint is available, the request must fail without silently attempting a paid route.
- Correct Groq 400 diagnostic: it is a rejected request/output contract, NOT evidence of quota exhaustion or billing. 429 is labeled rate-limit. No raw provider response bodies are shown to users.
- No automatic retries after uncertain network outcomes, HTTP 400, incomplete responses or a failed Groq fallback. The OpenRouter 429 -> Groq attempt continues to require the separately enabled website-source consent; `Groq Free/ZDR` also requires the Groq account actually be Free because LYRA cannot enforce an account-side zero billing limit.

## Verification / limits

`WorkspaceWebsiteGenerationTest` asserts zero-price primary route, format-required preference and HTTP 400 diagnostic. `WorkspaceWebsiteGroqFallbackTest` asserts no unsupported reasoning field, `include_reasoning: false` and exact strict JSON schema. GitHub Actions compiles and executes JVM tests; real provider traffic and physical phone acceptance are NOT represented by these tests. Repeat a new website build, a website edit, and a real HTTP 429 fallback on Android and check Preview and Undo before accepting phone pass.
