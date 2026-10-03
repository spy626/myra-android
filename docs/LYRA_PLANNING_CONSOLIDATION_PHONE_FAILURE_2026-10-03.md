# LYRA #3418 prompt consolidation and false rejection repair
Date: 2026-10-03. Required branch: agent/myra-phase-1. Source base: 910413fdb02aa019004aff2a746d8d979fcd2deb.

## Observed phone failure
The new phone recording shows: "LYRA did not follow your requested 3 numbered planning steps. Reply not saved". This is a local completed-answer rejection, not proof that the model gave only two meaningful actions. The raw rejected model response is unavailable in the recording.

## Replacement (not another competing layer)
- Remove WorkspacePlanningAnswerBoundary.instructions() and its appended second system prompt from WorkspaceChatGateway.
- WorkspacePlanningBrief continues to project CURRENT-turn facts only.
- WorkspacePracticalPlanningGuide.sharedContract() is the SINGLE planning behaviour contract, included once in normal Chat and once in Groq compact Chat.
- The same mandatory N-step, phone, budget and no-implementation boundaries are shared. Optional editorial presentation guidance can be shorter for Groq, but cannot contradict the core contract.
- The existing WorkspacePlanningAnswerBoundary remains a narrow completed-answer acceptance guard, NOT another prompt builder. It no longer requires the answer to parse as WorkspaceRichAnswerBlocks.Block.Numbered in order to save it.
- Clear labels such as 1., 2., 3., Step 1:, **Step 1:** and ### Step 1: are recognized when checking explicit main-step count. A reply without such numeric labels is not rejected merely for its visual formatting. Explicitly marked two/four-step replies still fail for an exact three-step request.
- Preserve the independent advice-only setup/implementation guard: installing builders, New Project and actual visual-block wiring are not planning.
- Do NOT auto-rewrite rejected provider outputs or automatically resend with paid or unconsented routes.

## Tests
- Latest video equivalent: three valid planning actions using Markdown Step headings and bold labels.
- Unmarked but substantive planning text must not trigger the formatting-only popup.
- Clearly numbered two/four-step answers remain detectable.
- Visual no-code implementation remains a violation of advice-only.
- Normal and Groq-compact projections each carry the shared mandatory contract exactly once.
- Full original latest user prompt preserved for all existing Free providers; current authority, memory and main unchanged.

CI success establishes compilation/tests/signatures, NOT physical phone acceptance; phone retest is still required.
