# LYRA beginner planning and response presentation — evidence and bounded adoption

Date: 2026-10-02. This is the #3394 phone-failure recovery slice. The recorded phone comparison found that an ambiguous "choose Thunkable, Kodular or AppGyver" menu and "watch a tutorial" did not answer which app to open now or what to do next. Three numbered steps and coding restraint alone are insufficient. Do not assert real-phone PASS from unit tests/CI.

## Collected ideas reviewed

The complete collection is in WORKSPACE_IDEA_LEDGER.md; source entries outside this task (browser control, model/provider catalog, voice, gaming, credential routing, code editing) are not actionable for this narrow response-quality issue.

Pinned upstream inspected (concepts, not copied upstream code or dependencies):

- github/spec-kit @ 5cc1f2a846c4c296adb45f46b72a9a0bc13883a7, README: separate the intended outcome/specification and planning from later implementation. Borrow WHAT/WHY first.
- obra/superpowers @ 5bf4e78011075bcfc0dc295f0724994cd123ee71, brainstorming SKILL: discover intent, present a bounded path, clarify only materially missing inputs; approval for discussion is not implementation consent.
- addyosmani/agent-skills @ bcab6a1b8503100e8618c3b4e32cc78de43de769, README: DEFINE → PLAN → BUILD → VERIFY; small ordered tasks with observable deliverables, not tutorial/tool-list dumping.
- nextlevelbuilder/ui-ux-pro-max-skill @ dcc40ff5133ef78276117db0cc34e7b83cc8aeba, README: project-specific hierarchy and context-aware recommendations; borrow the readability/hierarchy concept, not its CLI or design-system generator.
- moeru-ai/airi @ eeea3a1a5a061a33da076574ef5830f81a43392b, README, plus current LYRA unified architecture: preserve companion voice and existing source-owned Chat/context flow, not a second brain or planner.

Other collected relevant concepts already accounted for in the ledger:
- Mini-Agent: bounded context and logging.
- OpenHands: conversation/advice != action.
- SemIf-OpenJev: typed decisions versus uncontrolled free-form action.
- screenshot-to-code / taste-skill: visual output evaluation, not importing a new renderer.
- Super Agent Party / Neuro: natural conversational voice remains separate from mutation authority.

## Narrow integration into current Workspace Chat

1. The existing planning detector remains the gate for advice questions and does not authorize tool/file/build actions.
2. WorkspacePlanningBrief projects only conservative CURRENT USER turn cues: requested step count, explicitly phone-only and zero-budget, explicit delivery platform or UNSPECIFIED, digital-product request, explicit advice-before-execution. No old assistant statement or inferred user identity is promoted to authority.
3. Existing WorkspacePracticalPlanningGuide uses those cues to demand ONE clear default route, ONE exact tool to open NOW when relevant, its immediate action and a small expected result, and ONE optional implementation route for LATER. Do not give "A, B or C: choose one" to a beginner without stating a reasoned default. Phone hardware alone never dictates native Android output.
4. Presentation is part of the answer contract: direct opening sentence + reason; exactly the user's requested number of numbered MAIN steps, short bold step names, plain WHERE/WHAT/RESULT, readable line spacing, no dense menus or long acronyms, one 2-minute next action. Keep normal Hinglish. Do not use "watch tutorials" as a step.
5. For planning-only: no code, file creation, app installation or execution claim. Existing current-turn execution gate remains authoritative. No second model call, DB, provider, runtime, code writer, or new permission.

## Regression/physical acceptance

Automated tests check conservative cue projection, original user turn preservation, correct guidance position after incidental code-format hints, behavior across existing Free provider payloads, broad domains (grocery, booking, portfolio, guitar, volunteer event), unrelated casual/story requests, and mutation boundaries. These are prompt-contract tests; they cannot prove final free-model output quality.

Physical Android same-New-Chat test uses exactly:
"bro mere paas sirf Android phone hai aur mujhe free mein ek simple grocery app banana hai. Sabse pehle kya karna chahiye? 3 practical steps batao, abhi coding start mat karna 😂"

Report observed PASS only if:
- A beginner can identify exactly WHICH app/tool to open right now, WHAT action to take and WHAT small result to produce.
- Current planning tool and later implementation tool are distinct; no unexplained menu of competing builders.
- Three ordered doable actions, appropriate first-user MVP, Android/free boundaries, natural concise Markdown.
- No file/code/build side effect or invented free-tier verification.
- Compare separately against the ChatGPT recording; identical words, screenshots or recommendations are not the goal.

Stop after exact push CI terminal GREEN, then one-step phone retest. Main unchanged and no force-push.
