# LYRA phone screenshot follow-up: false planning rejection + Groq Free budget

Date: 2026-10-03. Working branch: agent/myra-phase-1.
Base: b03d1d61c51d133c9c391b6e6f5e6dd1dea1f.
Screenshots:
- "LYRA crossed your planning-only boundary into implementation (including no-code builder actions). Reply not saved."
- "Groq Free prompt too large ... 12,000-character local Free guard."

## Evidence and limits
The screenshots prove that the local post-generation guard rejected one completion and an earlier request was rejected at Groq's local preflight. They do NOT display the rejected provider's raw prose, so one cannot reliably call its contents an actual implementation instruction. The old Stage regex could mistake ordinary planning prose, quoted scenarios, negatives or descriptive future tools for implementation; the app suppressed the entire reply without showing it.

## Consolidated repair
- Keep WorkspacePracticalPlanningGuide as the ONE prompt contract for advice-only stages, counts, device and budget.
- Replace the old WorkspacePlanningAnswerBoundary regex-heavy prose rejection with a narrow explicit-language-tagged source-code fence check when the current user explicitly forbids coding. No new validator, new model, prompt layer or automatic paid/free provider retry.
- The independent WorkspaceExecutionAuthority and permission gates for *actual* tools, code/file mutation and task execution are not changed. Displaying model prose does NOT grant tool permissions.
- Groq Free: the existing 12,000-char conservative cap is retained. Use the SAME projected request for preflight and HTTP body. If over cap, use the existing compact advice contract where relevant and progressively omit only complete oldest previous turns from the temporary outbound message copy, dropping orphaned assistant turns too.
- Never truncate latest user message, required runtime/one-turn instructions or persistent chat history. If even latest+mandatory context does not fit, continue refusing without network or paid fallback.
- Make the remaining oversize popup accurate: older request history has already been compacted. Suggest shortening latest task or choosing another already-approved Free route; no false suggestion that starting a new Chat is required.

## Regression scope
- Exact latest screenshot grocery intent, ambiguous screen/create/connect planning prose, quoted tool names, future sections, negative warnings, normal casual conversations.
- Explicit source-code block still rejected only for advice-only; explicit implementation requests remain unaffected.
- Long 8-pair chat prunes earlier request-copy and retains latest user turn plus runtime contract; preflight/body match.
- Oversized latest and oversized mandatory instructions still block before sending. No silent provider switching, billing, data clearing or new storage.

CI green means unit/build/signature verification, not physical Android acceptance. No raw provider completion was available to assert its semantic correctness.