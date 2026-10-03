# LYRA Chat: answer quality and native presentation acceptance

Date: 2026-10-03. Source: user phone comparison, videos 1000063563.mp4 (LYRA) and 1000063564.mp4 (ChatGPT). Applies only to agent/myra-phase-1.

## Observed #3410 phone failure (not a model score)
The user had an Android phone only, zero budget and asked for three practical first steps for a small grocery app, explicitly NO CODING YET. LYRA displayed numbered sections but suggested installing Android Studio on Windows/Mac/Linux, Android SDK/emulator and creating XML layouts. That crosses the device and planning boundaries. ChatGPT's example gave customer-facing screens and an initial phone-friendly route with contextual sections; external imagery cannot be manufactured from an unsupported source.

## Architectural failure and changes
- A generic code keyword matched "coding mat karna". Suppress code-format instructions when the same current turn is clearly advice-only. Explicit coding requests remain eligible.
- WorkspacePlanningAnswerBoundary adds a brief, highest-recency current-turn contract: N requested steps, phone/device, no-cost, explicit native-vs-unspecified platform, no-code-before-plan, concise Roman Hinglish. It NEVER selects tools, invokes a model or grants execution.
- The *completed* reply is inspected before persistence, on the existing normal/custom text Chat paths, for unambiguous prohibited implementation instructions (desktop setup, project source files or fenced implementation code) only for an explicitly advice-only turn. Reject with a clear reason instead of silently deleting model text or retrying with a paid route. Safe comparisons and "do not install" warnings remain allowed.
- Native presentation groups nested detail bullets under their actual numbered step and recognizes standalone bold headings as sections. The original content, links, copied data and model's facts are not rewritten.
- Preserve ordinary casual/serious task paths, Roman Hinglish contract and Groq's 12k prompt guard. No extra provider, paid fallback, second memory or database.

## Regression and phone acceptance
- Unit tests verify the *actual bad reply* is rejected, a coherent three-step phone-first planning example survives unchanged, prohibitive language is not mistaken for a code request, fenced-code enforcement stays advice-scoped, and nested numbered bullets render as cohesive data.
- On real Android test the same grocery prompt and then varied advice prompts (portfolio, study plan) and casual conversation. The selected provider must remain eligible, answer 3 MAIN steps with no desktop setup or code, Roman Hinglish and readable native sections. CI GREEN is not a physical PHONE PASS. Do not claim equivalence to ChatGPT models or generate imaginary tool images.
