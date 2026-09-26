# Website single-key free provider recovery

Phone recording `1000061040.mp4` predates this implementation: user tested Groq and OpenRouter keys separately, not simultaneously. It is not an acceptance test of this change.

Fix scoped to `agent/myra-phase-1`: website generation now starts with a valid OpenRouter Free key when present; otherwise starts directly from an enabled Groq Free/ZDR key. When both are available and the previously saved website source-sharing opt-in is enabled, definitive OpenRouter 404/429/502/503/504 can fall back to one Groq Free attempt. No per-file or per-message approval dialog, no paid model, no Gemini route, no uncertain timeout resend. OpenRouter's `require_parameters` condition was dropped to avoid excluding otherwise eligible free endpoints that do not advertise JSON-mode parameter support. Its ZDR, denied collection and zero-price guard are unchanged, and the strict local three-file parser, secret scanning, stale-source protection and Undo are unchanged.

CI tests: Groq-only selection, OpenRouter-only selection, two-key selection, no assumed Groq Free/ZDR from a key alone, provider destination, zero-paid routing, existing website tests, and debug APK compilation.

Remaining limitation: an OpenRouter-only key cannot switch to Groq when no Groq key is saved. Free provider outages, no matching privacy-compliant endpoints, quota failures or incomplete generated code may still prevent generation. No real phone acceptance has been claimed. Do not remove project/source protections or bypass Android signing checks.
