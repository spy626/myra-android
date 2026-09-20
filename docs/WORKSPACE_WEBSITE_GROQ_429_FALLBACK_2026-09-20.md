# Work website Free 429 fallback (2026-09-20)

Branch: `agent/myra-phase-1`. This feature only changes Work WEBSITE builds, not normal Chat, voice, Android project file edits, AIRI memory, or the main branch.

## Behavior

1. The initial website build remains OpenRouter `openrouter/free`, with existing zero-price, ZDR, and no paid routing constraints.
2. If the final HTTP response is **429**, and both provider keys exist, **Groq Free/ZDR** and the separately labeled **website source fallback** permissions are enabled, send the same approved goal and selected `index.html`, `style.css`, `script.js` snapshot to the fixed Groq model `openai/gpt-oss-120b` once. The app does not send unrelated files, attachments, chats, voice, or memory; project source is checked for possible secrets and bounded before routing.
3. No switch for 402, 408, 5xx, unknown network outcomes, timeouts, malformed or partial answers. No Groq retry loop, even for HTTP 429 with Retry-After. If Groq fails, stop with a specific error; do not declare completion or overwrite project files. Safe Edit and durable Undo remain owned by the existing file store.
4. The new Settings toggle defaults OFF. Existing Chat permissions alone **never** authorize website source sharing. Users must keep their Groq account on Free and Inference APIs ZDR enabled: LYRA cannot verify the tier or impose a Groq API-side $0 ceiling, and paid Groq accounts can bill the same model.
5. Conservative 12,000-character combined prompt budget and 4,500 Groq completion-token cap mean very large websites can be rejected locally before sending. This is deliberate to reduce risk of hitting Groq Free token quotas; model output may still be incomplete.

## Validation

- JVM tests assert status-429-only eligibility, two independent opt-ins and saved-key requirements, exact selected-source envelope, Groq-only model/endpoint, bounded prompt, no OpenRouter provider controls, and no retry interceptor for the Groq call.
- CI runs `:app:testDebugUnitTest :app:assembleDebug`. A CI pass is **not** real Android phone acceptance. No live provider key or real-account billing test is performed.
- Phone acceptance: enable one-time settings, create a fresh website request when OpenRouter final 429 occurs, check progress states `trying Groq Free once`, inspect Work saved-project Preview, verify Chat success/failure result and Undo, then ensure no second resend on Groq failure. Provider may not produce a 429 on demand; do not claim this route phone-passed based solely on a normal successful OpenRouter request.
- Signing key must match installed app for in-place updates. If it does not, preserve local projects/keys before uninstalling; never disable Android installation verification.
