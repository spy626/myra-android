# Groq Free local routing regression — phone recording 1000063474.mp4

Date: 2026-10-02. Exact observed behavior on #3402: after submitting the grocery planning prompt, Chat displayed "No eligible Workspace free route" and "Message saved locally; no request was sent". The settings screen showed a saved Groq key and Groq Free/ZDR enabled, with OpenRouter and LLM7 empty/disabled. This is a local provider-selection failure; no evidence of a renderer crash or a provider HTTP response.

The selection path requires GroqFree.withinBudget(candidate, runtimeSelfModelInstructions). Its existing 12,000-character budget counted the full generated OpenRouter-oriented instruction stack, including the ~6k-character planning guide and substantial runtime / chat guidance. That can disqualify Groq even with a saved key and consent.

Fix: an explicit *compactForGroq* profile on the EXISTING WorkspaceChatGateway.openAiMessages, preserving the latest raw user turn, regular outbound history, required runtime instructions, task writing/code guidance and a concise planning brief. WorkspaceGroqFree both preflights and constructs the actual outgoing JSON with the SAME compact projection. The 12,000-character ceiling, Free/ZDR consent, OpenRouter/LLM7 paths, token limits and no-paid-fallback stay unchanged. If a genuinely oversized Groq prompt still cannot route, show its budget reason rather than claiming the user needs to add a missing key.

Regression test reproduces a phone-only, free-only, three-step, no-coding planning request with runtime instructions. Test checks selection fits the actual 12k cap, correct Groq route, original user text byte-for-byte, planning/no-coding boundaries and absence of OpenRouter-only billing/provider/plugin settings.

CI success is not PHONE PASS; install updated exact-SHA signed APK and retry the same request on physical Android.
