# LYRA Chat Roman Hinglish-only presentation follow-up

Date: 2026-10-02; branch: agent/myra-phase-1.

## Physical video evidence
- 1000063503.mp4: LYRA #3408 does produce separate numbered/section rows, but its substantive reply combines Devanagari Hindi with English labels, recommends Android Studio, Termux/AIDE and native project setup for a simple phone-only planning-before-coding prompt.
- 1000063504.mp4: the comparison ChatGPT answer keeps natural Roman Hinglish, differentiates basic screens and tool purposes, uses native-looking visual hierarchy, app icons and a sample-products comparison. This is an observation of these TWO runs, not a guarantee about either system's future output.

## Narrow correction
- Shared text Chat system language rule directs the existing OpenRouter, Groq, LLM7 and manually configured custom Chat route to write ALL explanatory text (headings, bullets and prose) in natural Roman Hinglish, with English technical words allowed. It does not transliterate user-authored turns or code.
- Completed native Chat assistant messages receive a local, deterministic ICU Devanagari-to-Latin script fallback only when needed. No second model call, translation API, budget increase, paid fallback, extra executor or memory owner.
- Markdown fences, inline code and URLs are preserved; original user turns are never rewritten. Older stored assistant replies have the fallback applied on screen only; Copy matches what is shown. Fresh assistant Chat replies are normalized after existing reply verification and before saving.
- Unit tests prove the prompt policy reaches every existing free Chat provider with latest user text unchanged and cover mixed headings, bullets, tables, inline code, code fences and URLs with an injected transliterator; on-device ICU behavior requires physical Android testing.

## Scope and limitations
Romanizing script cannot repair poor advice or make a weak model intrinsically as capable as ChatGPT. Native UI blocks are preserved from #3402, and no fake icon/cards/data are generated. Keep real phone acceptance separate from CI success.
