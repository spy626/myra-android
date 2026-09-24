# LYRA Unified Agent Architecture Blueprint — 2026-09-24

Status: architecture contract only. This document does not claim implementation or physical-phone acceptance.

Repository: `spy626/myra-android`
Required branch: `agent/myra-phase-1`
Audited LYRA HEAD before this document: `4769af32db8fcfbca4533b40d296f00a64db01ce`
Required recovery ancestor: `c307357b53ec4ad3a005769d55f29bc44d74ff41`
Main observed during audit: `c165cb906a31ab2a8148806044d67c340b7535fc`

## 1. Decision

Do not add a second brain, second memory owner, second provider router, or an independent autonomous writer.

LYRA should converge on one core with domain adapters:

```
User / Voice / Screen / Work
          |
          v
Authoritative Turn + Task/Context
          |
          v
Existing LYRA Core
  |       |       |
  |       |       +--> AIRI/Plast-Mem owner (one Room truth)
  |       +----------> Workspace task/spec/evidence contract
  +------------------> General action runtime / capability router
          |
          v
Relevant capability projection only
          |
          +--> Coding providers
          +--> Browser / Web / Agent-Reach adapters
          +--> Screen / Eye / phone-control adapters
          +--> GitHub / files / other approved tools
          |
          v
Canonical local execution owner
          |
          v
Observe actual result
          |
          v
Deterministic verification first
          |
          +--> PASS -> complete
          |
          +--> FAIL_FIXABLE -> bounded targeted recovery
          |
          +--> UNKNOWN -> re-observe / clarify, never duplicate a risky action
          |
          +--> BLOCKED -> stop with exact blocker
```

A model reply is a proposal or decision input. It is never proof that an action happened.

## 2. Existing LYRA components that already satisfy part of this design

Do not replace these.

### General action runtime

`GeneralAgentRuntime.kt` already owns a real state machine:
`CREATED / UNDERSTANDING / OBSERVING / PLANNING / READY_TO_ACT / ACTING / WAITING_FOR_RESULT / VERIFYING / RECOVERING / NEEDS_CLARIFICATION / COMPLETED / FAILED / CANCELLED`.

It already has:
- structured intents;
- relevant capability selection;
- fresh perception requirements;
- expected outcomes;
- action history;
- verification;
- rejected targets;
- recovery;
- modal safety;
- bounded recovery;
- UNKNOWN != SUCCESS;
- no second physical action when a prior action may already have occurred but verification is uncertain.

Preserve this owner for phone/screen/browser-device actions.

### Workspace task contract

`WorkspaceTaskContract.kt` already separates:
- goal/specification;
- acceptance criteria;
- spec revision;
- approval;
- bounded steps;
- allowed tools;
- expected evidence;
- final verification.

Its reconciler already refuses to treat model plan/progress text as evidence.

Preserve this as the project-coding task truth instead of introducing a second planning database.

### Workspace mutation owner

Existing Workspace Safe Edit / website generation / snapshots / stale-source checks / Undo / Keep remain the only project mutation path.

Provider output must never bypass these owners.

### AIRI / Plast-Mem

Keep one memory coordinator, one Room storage truth, one final-turn authorization path, one retrieval/consolidation architecture. Personal memory cannot silently authorize provider/source sharing.

## 3. Repository audit — concepts to borrow

The exact source should be rechecked again immediately before any code is copied. Most items below are concept ports, not dependency-install decisions.

### Memory / context

- `moeru-ai/airi` — audited main `eeea3a1a5a061a33da076574ef5830f81a43392b`.
  Borrow/retain Context Registry, task context ownership, transcript authority, Spark/proactive concepts, human-like companion separation from tool authority.
- `moeru-ai/plast-mem` — audited main `611103456d953c9a74452f4239817b3468f94bba`.
  Retain episodic + semantic memory, consolidation, NEW/REINFORCE/UPDATE/INVALIDATE lifecycle, hybrid retrieval and review semantics.

### Specification / engineering workflow

- `github/spec-kit` — audited main `5cc1f2a846c4c296adb45f46b72a9a0bc13883a7`.
  Borrow specification -> plan -> tasks -> implement -> converge, plus assess/fix/test separation.
- `addyosmani/agent-skills` — audited main `bcab6a1b8503100e8618c3b4e32cc78de43de769`.
  Borrow DEFINE -> PLAN -> BUILD -> VERIFY -> REVIEW -> SHIP, small verifiable tasks and mechanical QA.
- `obra/superpowers` — audited main `5bf4e78011075bcfc0dc295f0724994cd123ee71`.
  Borrow root-cause-first debugging, red/green evidence, plan execution, review gates. Do not copy its many-subagent execution pattern as LYRA's default.
- `OpenHands/OpenHands` — audited main `b0906809b3e8777491519c386d55ce32d7f4daa4`.
  Borrow conversation != action, explicit execution environment, task automation and agent/backend separation. Do not import its desktop/server runtime into Android.
- `MiniMax-AI/Mini-Agent` — audited main `d76a4f6389688cabda39c224a6cdfa274215d47c`.
  Borrow simple tool loop, persistent task notes, context management, logging and tests. Do not give unrestricted shell/filesystem authority.
- `open-jarvis/OpenJarvis` — audited main `e86c582bbe9672db7a8ba574326140cb4ce29655`.
  Borrow capability-scoped skills, scheduled/continuous task concepts and data-boundary preflight. Reject its local-model-first assumption for current LYRA constraints.
- `msitarzewski/agency-agents` — audited main `053ddbbf392a1688fc7043d81529f47ef2cf86c8`.
  Borrow logical specialist roles and explicit deliverables. Roles are responsibilities under one core, not independent brains/writers.

### Semantic decisions

- `TheoLeeCJ/SemIf-OpenJev` — audited master `23cf1f39fc9534fe81437200959b6dfc7106e45a`.
  Borrow typed runtime decision contracts for route/retry/verify/allow/confirm/block and the principle that small decisions should not require long free-form answers.
  Do not use its current 4B decision score as sole authority for risky actions; its published action-firewall result is not strong enough to replace deterministic policy.

### Context / token efficiency

- `mksglu/context-mode` — audited main `5a92b7caaf0086d04b87cc03a82505c891aa8254`.
  Borrow raw-output isolation, index-then-retrieve, session continuity and compact tool-result projection. Do not create a second task-memory truth.
- `trailhq/Graft` — audited main `f06070d702b501ce38a2fe0cf7191b9f579888ae`.
  Borrow cheap structural repo map, code API skeleton, impact context and freshness.
- `DeusData/codebase-memory-mcp` — audited main `1160fa3591ea2bbc20ab1f7b7f547466bb005a7d`.
  Borrow project knowledge graph, provenance, impact queries and bounded code retrieval. Do not create a competing personal-memory system.
- `tirth8205/code-review-graph` — audited default branch `staging` at `6b12d11625cbec3b6773e076cb3d136464fa90e5`.
  Borrow changed-file blast radius, affected-test selection and token-budgeted review context. Compiler/build/test output remains final technical authority.

LYRA token rule:
`deterministic local query -> compact evidence -> provider`, never `dump whole repo/log/browser snapshot -> provider`.

### Browser / internet / social

- `Panniantong/Agent-Reach` — audited main `a19a171fa980a0785849596492e0af4db800c82f`.
  Borrow capability-layer routing: URL/platform detection, primary+fallback readers, health diagnostics and explicit login/session requirements.
  Desired LYRA behavior: user gives a URL; LYRA selects the correct approved reader automatically.
- `browser-use/browser-use` — audited main `d8110c5ff87ccba887aaa726cdb780f2f84bef8d`.
  Borrow observe -> act -> verify browser workflow and real-browser abstraction. Hosted cloud paths are not assumed free.
- `sujan1-3/browser-eyes-mcp` — audited main `80b85f441838ff8cbb4fec0a431b7396cae0d5ec`.
  Borrow browser evidence planes: screenshot + DOM + accessibility + console + network + storage + performance. Do not expose unrestricted CDP mutation tools by default.
- `social-cli/social-claw` — audited main `5705429e6b1a726dcfce3a4361f7733268de9631`.
  Borrow normalized social-channel capability contracts and scheduled/queue concepts. Its service/API entitlement is separate from its open-source runtime.
- `SoCloseSociety/MiloAgent` — audited main `d908d0696f1f26ca21114954c4cf2c38db20fc5e`.
  Borrow rate limits, per-community tone/context and outcome learning concepts only. Do not add autonomous promotional/spam behavior.
- `heshengtao/super-agent-party` — audited main `b3af7a0b4a3d15c23d0206f57bde3caa625b6f1c`.
  Borrow Task Center, computer control, AI browser, extension/tool concepts and companion personality separation. Avoid uncontrolled subagent swarms.
- `kimjammer/Neuro` — audited master `5e4b4241c41bb40983aee2cb60d65d6bb481842b`.
  Borrow event-driven conversational timing and proactive/reactive interaction concepts.
- `VedalAI/neuro-sdk` — audited main `0cad33ac692f6cc90d36747fea9bbc53ca34b97a`.
  Borrow high-level action contracts and state/action separation. Game mode remains deferred.

### UI generation / visual QA

- `abi/screenshot-to-code` — audited main `d026163f586dfa8c5c10d28c36edd59a9d3b0e88`.
  Borrow screenshot/recording -> implementation -> render -> visual-check refinement loop.
- `nextlevelbuilder/ui-ux-pro-max-skill` — audited main `dcc40ff5133ef78276117db0cc34e7b83cc8aeba`.
  Borrow design-system, responsive/accessibility and stack-specific QA checklists.
- `Leonxlnx/taste-skill` — audited main `c184364c58658b2f131b4ae8bd3d206cabb3deee`.
  Borrow anti-generic visual review and design-language discipline; do not allow it to override an approved LYRA design.

### Provider / route architecture

Use these only as routing/health/accounting references; exact free entitlement must be independently verified at implementation time.

- `diegosouzapw/OmniRoute` — quota-aware routing, provider catalog, fallback and token-compression ideas.
- `vava-nessa/free-coding-models` — live latency/stability probes, capability metadata, quota headers and health scoring.
- `ihsan-ramadhan/bansos-router` — fail-closed provider allowlists and cross-provider-fallback controls.
- `decolua/9router` — tool-result compression and quota-aware fallback concepts.

Never import "subscription -> cheap -> free" fallback ordering into LYRA. LYRA remains verified-free-only with no paid fallback.

### Lightweight agent runtimes

Borrow patterns, not runtimes:
- PicoClaw: lightweight capability registry, hooks/event bus, Android awareness.
- NullClaw: explicit allowlists, workspace scoping, pluggable providers/tools.
- ZeroClaw: supervised risky actions and tool receipts, after provenance review.
- IronClaw: host-bound credential injection, capability permissions, isolated parallel jobs.
- NanoClaw: narrow mounts, credential gateway, scheduled-task gates.
- Nanobot: durable task status/checkpoints and small readable core.
- SafestClaw: do deterministic work locally and avoid LLM calls when unnecessary.

## 4. Unified Work task lifecycle

For serious coding/work tasks, the target lifecycle is:

```
SPECIFY
  -> PLAN
  -> CONTEXT_SELECT
  -> PROVIDER_SELECT
  -> PROPOSE
  -> VALIDATE_OUTPUT
  -> APPLY_THROUGH_EXISTING_OWNER
  -> OBSERVE
  -> VERIFY
     -> PASS -> RECONCILE -> COMPLETE
     -> FAIL_FIXABLE -> DIAGNOSE -> TARGETED_RECOVERY -> VERIFY
     -> UNKNOWN -> REOBSERVE / CLARIFY
     -> BLOCKED -> CHECKPOINT -> STOP
```

### Completion invariant

A task is COMPLETE only when required acceptance criteria are backed by trusted evidence.

Examples:
- source edit: expected file diff + source freshness + parser/shape checks;
- Android change: relevant tests + build/CI where required;
- website: saved files + Preview/DOM/interaction checks;
- browser/phone action: fresh post-action observation matching expected outcome;
- GitHub task: remote commit/ref/CI evidence;
- user-facing phone pass: physical Android evidence only.

"Model says done" never satisfies this invariant.

## 5. Bounded recovery contract

Default recovery budget: at most 2 targeted recovery cycles after the initial attempt unless a narrower existing provider contract allows fewer.

Classify failure before retry:

- `DETERMINISTIC_LOCAL`: parser, validation, compile/test, stale source, missing expected file. Fix locally or create a targeted new proposal.
- `DEFINITIVE_PROVIDER_REJECTION`: only use a separately approved compatible free fallback.
- `UNCERTAIN_NETWORK_OUTCOME`: do not resend a mutation-bearing request automatically.
- `VERIFICATION_UNKNOWN`: re-observe; do not repeat the physical action.
- `WRONG_TARGET`: reject target/strategy and replan once.
- `SAFETY_OR_PERMISSION`: stop/ask; never route around it.
- `QUOTA_OR_COST_BOUNDARY`: checkpoint and stop or use a pre-approved verified-free route.
- `NON_FIXABLE`: report exact blocker.

Each recovery must change something relevant: target, context, strategy, provider after eligible definitive failure, or implementation. Blindly repeating the same request does not count as recovery.

## 6. Verification hierarchy

Prefer the cheapest trustworthy evidence first:

1. deterministic local checks;
2. parser/schema/structural checks;
3. diff/source-freshness/impact checks;
4. unit/static tests;
5. build/integration/CI;
6. browser DOM/network/console checks;
7. visual/screen observation;
8. independent LLM review only where semantics still require judgment;
9. physical phone evidence for phone acceptance.

An LLM verifier cannot override a deterministic failure.

## 7. Token/context architecture

Do not solve context limits by deleting the newest user instruction.

Context assembly order:

```
current authoritative user turn
+ task acceptance criteria
+ current run state
+ only relevant project symbols/files
+ dependency/blast-radius evidence
+ bounded recent conversation
+ approved relevant memory
+ compact tool evidence
```

Keep raw logs, full repo trees, large browser snapshots and historical tool output outside the provider prompt. Index/search them locally and project only the relevant result with provenance.

Track:
- estimated outgoing tokens/chars;
- source files included;
- reason each source was selected;
- raw bytes avoided;
- provider/model;
- response completeness;
- task attempt/recovery count.

## 8. Provider-role model

Provider roles are logical capabilities, not separate brains.

Current architecture boundaries remain:
- Gemini: voice lane only unless a future explicit redesign is approved.
- LLM7: ordinary Workspace text route when enabled; no source-bearing Work mutation.
- Z.ai GLM-4.7-Flash: coding-only, sticky when explicitly enabled; no hidden cross-provider retry.
- xKiro: Work coding route with free/quota preflight and existing optional bounded free fallback.
- Groq/OpenRouter: only within their existing consent/cost/privacy contracts.

The future router selects by:
`task capability + context budget + privacy/source scope + current free eligibility + health + output requirement`.

Never select purely because a route is free.

## 9. Internet / social capability architecture

Add one capability family under the existing action/tool registry, not one brain per website.

Conceptual adapters:
- URL reader;
- GitHub;
- YouTube/transcript;
- Reddit;
- X/Twitter;
- RSS;
- general browser;
- approved logged-in social session;
- future Telegram/social communication.

Read/search and write/post are separate capabilities.

A user giving a link authorizes reading that link within the relevant permitted public/login context; it does not automatically authorize posting, messaging, following, deleting, purchasing or account changes.

Social writes need explicit action intent and platform/account scope.

## 10. Browser Eyes architecture

LYRA Eye and Browser Eyes are different evidence sources.

- LYRA Eye: Android current-screen perception.
- Browser evidence: tab screenshot + DOM/accessibility + console + network + storage/performance when available.

Unify them at `PerceptionSnapshot / evidence`, not by creating another "BrowserBrain".

For website verification, DOM/console/network can prove failures that a screenshot cannot. Screenshot/visual evidence can prove layout failures that DOM alone cannot.

## 11. Parallelism

Parallel work is allowed only for independent, read-only or isolated tasks.

Safe examples:
- code impact analysis + docs lookup;
- provider health probes;
- independent semantic review;
- browser checks on isolated copies.

Mutation rules:
- one canonical writer per project/file;
- no two providers write the same project concurrently;
- stale-result rejection by run/task/spec/source revision;
- results merge only through the local execution owner;
- uncertain timeout never triggers a duplicate write.

## 12. What not to build

Do not add:
- another MemoryBrain/DB;
- a second general action runtime;
- another provider router;
- a provider that writes files directly;
- free-form "model says retry" authority;
- uncontrolled agent swarms;
- exact-prompt phrase patches;
- whole-repo prompt dumping;
- silent cookie/session extraction;
- hidden paid fallback;
- local/offline LLM backbone under the current cloud-only preference;
- autonomous social spam/growth posting.

## 13. Implementation order

### Slice A — accounting + shared verification vocabulary
Documentation/tests only where possible:
- formalize evidence types, failure classes and completion invariant;
- map existing `GeneralAgentRuntime` and `WorkspaceTaskContract` states;
- add no new executor.

### Slice B — Workspace self-verification
Extend the existing Workspace task/coding path:
- persist current run/attempt/failure evidence under the existing task owner;
- deterministic verify after apply;
- PASS / FAIL_FIXABLE / UNKNOWN / BLOCKED;
- max two targeted recoveries;
- no provider output can mark complete.

### Slice C — token-efficient project context
Add deterministic project structure/impact indexing concepts:
- symbol/file map;
- changed-file blast radius;
- relevant-source selection;
- bounded evidence projection;
- no second memory database.

### Slice D — provider capability/health registry
Unify metadata for current providers:
- task type;
- source/attachment permission;
- context/output budget;
- verified-free state;
- cooldown/definitive error state;
- sticky/fallback rules.

### Slice E — Agent Reach-style link capability
URL/platform detection + approved read adapters + provenance + fallback health.

### Slice F — browser verification
DOM/accessibility/console/network evidence where available, with Eye/screenshot as complementary visual evidence.

### Slice G — personality/proactive layer
Neuro/Super-Agent-Party-style natural behavior and Spark-triggered proactive conversation, while serious Work remains bounded by the same task authority.

## 14. First implementation slice acceptance

Do not start with a large multi-agent rewrite.

The first code slice after this blueprint should prove one narrow generic behavior:

1. A Workspace coding task has explicit acceptance criteria.
2. Existing owner applies one approved change.
3. LYRA runs deterministic verification.
4. Verification failure is classified.
5. One targeted recovery can be attempted without overwriting unrelated work.
6. Verification runs again.
7. Completion is recorded only on PASS.
8. UNKNOWN does not become PASS and does not duplicate an uncertain mutation.
9. Stop/cancel prevents further provider/action attempts.
10. Existing website Preview, Safe Edit, Undo/Keep, provider privacy/cost gates and normal Chat behavior remain unchanged.

Only after CI passes should a phone-test APK be produced. CI is not PHONE PASS.
