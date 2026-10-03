# Work website: 404 free-route recovery — 2026-09-20

Phone recording `Screen_Recording_20260920_113516.mp4` shows both provider keys and Groq Free/ZDR plus website-source fallback enabled. The website request returns OpenRouter HTTP 404; a normal `Hii` chat reply does not exercise the website generation route. The previous website fallback only switched on 429, so the recorded 404 displayed a redundant Retry dialog instead of trying Groq.

## Changes
- Definitive OpenRouter free website HTTP 404/429/502/503/504 rejection can start exactly one separately authorized Groq Free website request. The existing, user-enabled website source permission is reused without per-request dialogs. Never resend after an unknown network outcome or a timeout; never try a paid model.
- The first HTTP error response is closed before the alternate request. The Groq client still has no auto-retry interceptor. The same task and fixed three-file snapshot are used. Validated generation and durable Undo remain in place.
- Coding failure is saved once in Chat. No additional error dialog or duplicate status banner is shown. A success still opens the saved website Preview; a failure must not be reported as a successful edit.
- Unit regression covers allowed and excluded HTTP statuses, missing permission, missing/invalid Groq keys and existing strict response schema.

## Acceptance not yet proven
GitHub unit tests and APK build are not a physical phone pass. On Android, send one website request (without sending `Hii` first); verify that if OpenRouter reports 404 the same turn automatically tries Groq, then saves all three files and shows Preview. If both free providers genuinely refuse, LYRA must report one final failure without false completion. Verify Undo/Keep separately. A free-provider success cannot be guaranteed when both free quotas are exhausted or a model is unavailable.
