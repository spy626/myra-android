# LYRA research-to-implementation: answer semantics + native composition

Date: 2026-10-03; branch: agent/myra-phase-1
Research basis: LYRA_ChatGPT_Research_2026-10-03.md, official public OpenAI guidance, AIRI core-agent and assistant-ui as independent DESIGN REFERENCES (not private ChatGPT source).

## Scoped change
- Keep a single existing Workspace Chat/Memory/authority architecture. No new model, DB, provider, tool access, paid route or automatic resends.
- Existing PracticalPlanningGuide is condensed from overlapping verbose guidance to one current-turn, task-focused staged brief. Groq compact projection and latest full user turn remain protected.
- Existing PlanningAnswerBoundary now models semantic work stages. Planning/sketches are different from SETUP (install ANY builder, initialize New Project) and IMPLEMENTATION (make actual screens/connect visual blocks/code). A source quote or separate Later discussion is not permission for today's setup.
- Validation examines the completed original provider answer before persistence. Explicit requested N planning steps must be represented as N numbered primary actions. Failing answer is rejected transparently, never silently rewritten or auto-paid-retried.
- Markdown answer presentation retains up to FOUR comparison columns as phone-native vertical cards with header/value relationships. Nested numbered details and casual reply preservation remain intact.
- Generic Chat instructions encourage coherent directions, aligned real comparisons and provenance honesty; no fictitious media or citations.

## Acceptance cases
- #3410 Android Studio/SDK/XML: prohibited for phone-only advice.
- #3412 Sketchware -> New Project -> create screens/connect blocks: prohibited for advice-only independently of vendor name or typed-code syntax.
- A setup order followed by 'do not code yet' remains setup and is blocked.
- 'Don't install builder' and a separate descriptive Later note do NOT count as prohibited.
- Three valid planning actions (scope, rough sketches, sample content) survive without edits; too few/many main steps fail clearly.
- Explicit actual build request and casual chat are unaffected; tools still need existing independent permission gates.
- 4-column comparison: all values preserved in both rich and fallback mobile presentation.
- Free route prompt budget, no fallback billing, signed APK identity and clean feature-branch ancestry must be verified in CI. CI green != physical PHONE PASS.

## Boundaries
This does NOT recreate ChatGPT private model weights, live search/media, streaming protocol or hidden orchestration. Rich images/charts require future verified data sources and user authorization. AIRI/Plast memory stays unchanged. No source from AGPL or unknown-license repositories was copied.