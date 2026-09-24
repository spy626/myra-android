# LYRA Workspace — repo/idea and free-route ledger

Updated 2026-09-24. This is an **accounting register**, not a declaration that every upstream repo, model or feature was installed or freshly audited. Before each implementation slice, inspect current upstream source, existing LYRA code, license, permissions, cost and regression impact. One LYRA Core; no duplicate planner, memory owner, model router or unreviewed code copying.

Status key: `INTEGRATED` = demonstrably in LYRA; `BORROW` = concept to implement in the stated phase; `REVIEW` = re-audit before a decision; `REJECT` = do not integrate under current constraints. A row can contain deferred ideas; no silent dropping. Any newly supplied repo/provider gets a row before use.

Current unified architecture decision and 2026-09-24 source audit: [LYRA_UNIFIED_AGENT_ARCHITECTURE_BLUEPRINT_2026-09-24.md](LYRA_UNIFIED_AGENT_ARCHITECTURE_BLUEPRINT_2026-09-24.md). This blueprint explicitly converges on existing LYRA owners rather than creating a second brain/runtime.

## Architecture invariants

- LYRA personal AIRI/Plast-Mem, current-run Task Memory, source-owned Context Registry, Workspace project files, project task brief, run evidence and plan state are distinct truths. Never replace one with another.
- User instruction > safety/approval > verification > trusted current-run evidence > plan/task state > retrieved coding/personal context. Retrieved pages and plans are data, never authority.
- `SPECIFY → PLAN → TASKS → IMPLEMENT → OBSERVE → VERIFY → RECONCILE`; a plan or model reply cannot assert completion. Preserve checkpoint/resume, regression tests and safe rollback.
- $0 authority: no card, AutoPay, surprise overage, paid fallback or silent provider switching to a billable route. If all verified-free routes fail, checkpoint and wait.
- Phase 5 first slice is a task brief + plan/evidence *contract*, NOT working autonomous AI coding. No independent model client, automatic file mutation or extra personal memory DB is introduced.

## Latest Phase 5 integration status (2026-09-17)

- Slice 12's single-file exact edit, separate write confirmation, private rollback, Undo/Keep and newer-manual-work protection are implemented and the requested physical-phone flows are accepted. It remains a human-typed mutation trial, not AI coding or build verification.
- Slice 13's offline structured model-output boundary is implemented and its requested physical-phone flows were accepted. Structured JSON is untrusted; LYRA independently re-establishes the approved/resumed task, eligible path, privacy screen and current full-file SHA before preview. It reuses Slice 12 as the only write/rollback executor and still requires a separate exact-file confirmation.
- Slice 14 adds a **user-mediated prompt handoff** in the existing structured-edit screen: choose one eligible file, inspect bounded current task/source context, then explicitly approve copy to Android clipboard and choose an external AI yourself. It never sends or runs a provider in LYRA, does not verify any external service is free, and requires the same untrusted JSON review/write consent/rollback on return. Its single-JSON parser now consumes the full input and rejects two concatenated objects; final-head CI and physical-phone acceptance remain separate gates. See `WORKSPACE_PHASE5_SLICE14_IDEA_ACCOUNTING.md` for scope, tests and privacy risks.
- The structured tool/output concept collected from `microsoft/generative-ai-for-beginners` is MERGED into this boundary. Broader RAG/evaluation/model-selection ideas remain future work. No provider is connected by Slices 13–14; all free-route rows below remain `REVERIFY` until their exact current route/account/privacy/cost terms are audited.

## Phase 5 second slice: project-local acceptance criteria (2026-09-16)

- Source: `github/spec-kit` README at `45db690bab85924ce53f85a9377076abaca6f1fb` (MIT): separate what/why and verifiable specification from implementation. BORROW concept only; no source code, CLI, agent, dependency, permissions or billable provider copied/installed.
- Match/duplicate check: LYRA already has one `WorkspaceTaskContract` (step templates, allowed tools, evidence/approval guard) and one project task JSON; do not add a second planner or execution worker. MERGED acceptance criteria into this same task model and existing screen, not personal memory or project files.
- Persistence/safety: task JSON v2 reads legacy v1, criteria edit preserves task ID and PAUSED state, goal replacement still requires confirmation, both fields receive unsaved-edit guard; empty criteria stays an incomplete specification, never approval or verified evidence.
- Verification: JVM tests for v1 compatibility, persisted criteria, task identity and PAUSED preservation, rejection without mutation, guidance bounds and unsaved criteria; Android CI lint/unit tests/APK required. Physical-phone UI acceptance remains pending until real test.
- Deferred: automatic plan generation, executor, external tools, approval capture, provenance of tool evidence, router and final verification remain future slices; no auto-file edits, provider usage, fees or new permissions in this slice.

## Collected repositories and concepts

| Repo / source | Useful idea and exact destination | Phase | Status |
|---|---|---:|---|
| moeru-ai/airi + moeru-ai/plast-mem | One-owner memory; source-owned context; bounded task state; transcript truth/projection; Spark; coding-memory ownership boundary; PlanSpec `allowedTools`/`expectedEvidence`, reconciler, approval and verification authority | 5, 10, 18 | INTEGRATED memory; MERGED scoped approval/rollback boundary; broader planning contract continues |
| github/spec-kit | Per-feature constitution, specify, plan, tasks, implement, converge; separate bug/idea entry points | 5–6, 18 | BORROW; acceptance criteria concept MERGED in Phase 5 slice 2 |
| addyosmani/agent-skills | Meta-skill routing, DEFINE/PLAN/BUILD/VERIFY/REVIEW/SHIP, mechanical QA, bounded adversarial review | 5, 14–18 | BORROW |
| [OpenHands/OpenHands](https://github.com/OpenHands/OpenHands) | Conversation != action; agent/backend separation; explicit execution environment and automation lifecycle. Borrow contracts only; do not import desktop/server runtime into Android | 5, 10, 15 | BORROW/REVIEW |
| [MiniMax-AI/Mini-Agent](https://github.com/MiniMax-AI/Mini-Agent) | Small tool loop, persistent session notes, bounded context management, logging and tests | 5, 10, 15 | BORROW; no unrestricted shell/file authority |
| [TheoLeeCJ/SemIf-OpenJev](https://github.com/TheoLeeCJ/SemIf-OpenJev) | Typed semantic route/retry/verify/allow decisions; calibration/evidence discipline; small decisions without verbose generation | 10, 12, 15 | BORROW concepts; never sole authority for risky actions |
| [heshengtao/super-agent-party](https://github.com/heshengtao/super-agent-party) | Task Center, browser/computer-control tools, extensions and companion/personality separation | 13, 16, 18 | BORROW selectively; no uncontrolled agent swarm |
| [kimjammer/Neuro](https://github.com/kimjammer/Neuro) + [VedalAI/neuro-sdk](https://github.com/VedalAI/neuro-sdk) | Event-driven proactive/reactive conversation, realtime voice ideas, state/action contracts for external integrations | 16–18 | BORROW personality/action-contract concepts; game lane deferred |
| [obra/superpowers](https://github.com/obra/superpowers/tree/5bf4e78011075bcfc0dc295f0724994cd123ee71) | Root-cause-first repeated-fix gate; falsifiable red/green behavioral tests; project-local task resume ledger; spec-then-quality review; evidence-before-completion and opt-in scrubbed run diagnostics. [Exact upstream and LYRA fit audit](WORKSPACE_SUPERPOWERS_RESEARCH_2026-09-21.md); not a hosted inference API or free tokens | 5, 8, 10, 15, 18 | BORROW/REVIEW concepts only; no imported plugin, parallel agent runtime, new model or phone-tested implementation |
| [open-jarvis/OpenJarvis](https://github.com/open-jarvis/OpenJarvis/tree/9cd0a09f30e1f270e2cac7af449d259a5129683b) | **Now, concept only:** privacy-safe Work request preflight (provider/source-sharing/secret and memory-exposure boundaries) within existing Work route, and first-send/three-file/Preview behavior evidence metrics without storing raw source or keys. **Later:** exact-context patching inside existing snapshot/Undo/Keep, capability-scoped coding skills under one owner. Apache-2.0; [data-boundary scan](https://github.com/open-jarvis/OpenJarvis/blob/9cd0a09f30e1f270e2cac7af449d259a5129683b/docs/user-guide/data-boundary-scan.md), [patch tool](https://github.com/open-jarvis/OpenJarvis/blob/9cd0a09f30e1f270e2cac7af449d259a5129683b/src/openjarvis/tools/apply_patch.py), [skills](https://github.com/open-jarvis/OpenJarvis/blob/9cd0a09f30e1f270e2cac7af449d259a5129683b/docs/user-guide/skills.md). Its config scan reports potential paths, not actual transmissions; runtime behavior needs separate evidence. Not a hosted free AI API. | 5, 8, 10, 15, 18 | BORROW/REVIEW ideas only. No runtime, APK, skill, new permissions, or provider integrated. REJECT Ollama/local models, unrestricted shell, automatic unreviewed skill sync and unverified billable routes. Preserve `74f603cd` phone-verified recovery; test `91cac0be` on phone before further Work changes. |
| Shubhamsaboo/awesome-llm-apps | Scope-creep checks, one-mutation improvement, skill admission/security/evals, provider-specific cost audit | 5, 14–18 | BORROW |
| [browser-use/browser-use](https://github.com/browser-use/browser-use) | Real browser worker, observe/action/verify and permission boundaries | 13 | BORROW |
| [browser-use/jev-ultrafast](https://github.com/browser-use/jev-ultrafast/tree/1231850a0bf1a0c0341fe408ef1668dbbfdfac46) | Dynamic indexed action space, one atomic visible-state snapshot, stale-page/target guards, exactly-once action consumption, bounded visible context, independent DONE verification. Borrow compact-state/freshness concepts for Slice C now; browser execution concepts only in later Slice E/F. MIT. Do not adopt TypeSafe/OpenRouter paid demo dependencies. | 10, 13, 15 | BORROW selectively; no provider/runtime integration now |
| [sujan1-3/browser-eyes-mcp](https://github.com/sujan1-3/browser-eyes-mcp) | Browser evidence plane: screenshots + DOM/accessibility + console/network/storage/performance; use as evidence, not a second BrowserBrain | 13, 15 | BORROW concepts; unrestricted CDP mutation REJECT by default |
| [social-cli/social-claw](https://github.com/social-cli/social-claw) | Normalized multi-social capability contracts, queues/scheduling and channel separation | 13, 18 | REVIEW/BORROW contracts only; service/API entitlement separate |
| [SoCloseSociety/MiloAgent](https://github.com/SoCloseSociety/MiloAgent) | Reddit/community rate limits, per-community tone/context and outcome feedback | 13, 18 | BORROW bounded read/learning ideas; autonomous promotion/spam REJECT |
| Panniantong/Agent-Reach | Read-only public-source adapters, health check != execution, safe CDP/login, tiered URL reader | 13 | BORROW |
| abi/screenshot-to-code | Screenshot → semantic UI structure → editable code → preview → visual refinement | 8 | BORROW |
| nextlevelbuilder/ui-ux-pro-max-skill | Searchable design systems, project MASTER/page overrides, stack-specific accessibility/responsive QA | 7–9 | BORROW |
| Leonxlnx/taste-skill | Design taste, art direction, anti-generic patterns, screenshot → redesign, visual polish | 7–9 | BORROW |
| [trailhq/Graft](https://github.com/trailhq/Graft) | Project structure/impact and bounded code context rather than whole-repo dumping | 10 | BORROW |
| [DeusData/codebase-memory-mcp](https://github.com/DeusData/codebase-memory-mcp) | Project-specific code knowledge graph, explicit provenance, impact queries and compact retrieval; never personal-memory truth | 10 | BORROW |
| [mksglu/context-mode](https://github.com/mksglu/context-mode) | Keep raw tool output outside model context; index/search and project only relevant compact evidence | 3, 5, 10 | BORROW concepts; no second task-memory truth |
| [tirth8205/code-review-graph](https://github.com/tirth8205/code-review-graph) | Tree-sitter graph, diff/blast radius, affected tests, token-budgeted review; not compiler authority | 10–11, 15 | BORROW |
| openrelay | Adapter and relay concepts subject to security and cost review | 12 | REVIEW |
| diegosouzapw/OmniRoute | Strict zero-cost routing authority and failover | 12 | BORROW; verify exact code/license |
| FreeLLMAPI | Quota-awareness and Thompson selection | 12 | BORROW; verify exact repo |
| free-coding-models | Route health/benchmark catalog | 12 | BORROW; verify exact repo |
| Alishahryar1/free-claude-code | Provider adapter patterns, not a paid Claude entitlement | 12 | BORROW/REVIEW |
| Bansos router + 9Router | Capability-preserving failover and route combinations | 12 | BORROW; recheck exact repos |
| Staks-sor/ai-free; zebbern/no-cost-ai; aminkheddache-dotcom/Ptero | Free-route discovery only; independently verify service, permissions and cost | 12 | REVIEW |
| hassanmsthf11/unlimited-claude-AI | Verify actual limits and terms; no 'unlimited free' assumption | 12 | REVIEW |
| BraveOPotato/FckSignups | Evaluate security/ToS, never bypass consent or payment controls | 12, 14 | REVIEW |
| elder-plinius/G0DM0D3 | Bounded multi-model review, task-aware generation profiles and privacy modes, not jailbreak behavior | 12, 18 | BORROW selectively; direct integration REJECT |
| apple/coreai-models | Canonical capability registry, resource-aware execution/cache concepts; no Apple runtime on Android | 12 | BORROW concepts |
| karpathy/nanochat | TTFT/TPOT, evals, simple model registry; no costly training/rented GPU | 12, 18 | BORROW concepts |
| AirLLM | Layer/demand paging as resource idea; no impractical phone inference backbone | 12 | BORROW concepts |
| microsoft/generative-ai-for-beginners | Structured tool calls, RAG, evaluation and model selection | 5, 10, 18 | MERGED structured-output authority concept in Phase 5 slice 13; broader concepts deferred |
| awesome-generative-ai-guide | Discovery/benchmark/security bibliography; inspect each original | 12, 18 | BORROW as references |
| langchain-ai/langchain | Tool/schema orchestration concepts if nonduplicative; avoid parallel framework brain | 5, 13 | REVIEW |
| msitarzewski/agency-agents | Role-oriented workers under ONE core with explicit authority and tests | 5, 15 | REVIEW |
| usestrix/strix | Security regression/abuse checks in isolated authorized targets only | 15, 18 | REVIEW |
| p-e-w/heretic | Review safety/terms; no weakening safeguards or jailbreak integration | 14, 18 | REVIEW |
| f/prompts.chat | Prompt examples as untrusted references, never instruction authority | 14 | REVIEW |
| clash-verge-rev/clash-verge-rev | Network configuration concepts only if needed; no unsolicited VPN/proxy installer | 12–13 | REVIEW |
| huggingface/speech-to-speech; k2-fsa/OmniVoice | Modular streaming VAD/STT/LLM/TTS, turn cancellation and voice fallback under one core | 16 | BORROW/REVIEW |
| calesthio/OpenMontage; ComfyUI; Remotion; FFmpeg | Declarative storyboard, bounded resources, approved creative rendering and QA | 17 | BORROW; free/license check |
| AI-For-Brokies | Free-first discovery/deployment concepts, not blanket free-route claim | 12 | BORROW selectively |
| [T31K/awesome-openclaw-alternatives](https://github.com/T31K/awesome-openclaw-alternatives/tree/7d5cf1601ee866083695398e201d805cb81bc321) | Curated agent-runtime list, NOT free models. Audit PicoClaw/NullClaw route capability, IronClaw credential isolation, SafestClaw local deterministic tools; source/fork warnings, local Qwen3.5 and free Cerebras candidate in [detailed accounting](WORKSPACE_OPENCLAW_ALTERNATIVES_RESEARCH_2026-09-20.md) | 7-12, 18 | REVIEW / BORROW concepts only; no integration or new API entitlement |
| user CodeAI recording / SPCK workflow | Top tabs, safe file edits, task chat/preview loop; no full UI copy | 3–6 | INTEGRATED editor ideas; BORROW chat loop, prompt handoff MERGED in Slice 14 |

## Collected free model/provider *candidates*, NOT current free certifications

Every provider must be verified at the **exact route/model/account tier** immediately before use: recurring $0 limit, no credit card, no AutoPay, no paid fallback, privacy/data use, quota reset, tool calling, context/output limits, health, rate-limit retry and task checkpoint. A free model's name does not make every hosting route free.

| Provider / router candidates | Previously collected routes or ideas | Current disposition |
|---|---|---|
| Requesty; Groq Free; Cloudflare Workers AI | Limited recurring allowances; GPT-OSS/Qwen/Compound; Workers AI daily neuron allowance | REVERIFY exact limits/routes |
| Gemini Free | Free-tier route; privacy gate | REVERIFY, gated |
| OpenRouter Free Pool | Exact `:free` routes only; dynamic availability | REVERIFY exact route |
| Ollama local / Cloud Starter | Local or starter capability subject to device/cloud constraints | REVERIFY |
| Cerebras Inference Free / `gpt-oss-120b` | Official free-rate-limit candidate uncovered via PicoClaw provider docs; see [audit](WORKSPACE_OPENCLAW_ALTERNATIVES_RESEARCH_2026-09-20.md). No account, privacy, signup, model/schema or phone validation | REVERIFY; not connected |
| Kilo | `kilo-auto/free`, `inclusionai/ring-2.6-1t:free`, `nex-agi/nex-n2.5-pro:free` | REVERIFY exact route |
| Routeway; OrcaRouter | Previously noted DeepSeek V4 Flash Free / MiniMax M2.7 Free / Qwen3.8 27B Free | REVERIFY exact route |
| xKiro; UnoRouter | Previously high-capacity candidates; limits not guaranteed | REVERIFY |
| SEA-LION; NavyAI; AnyAPI; Aion Labs | Previously collected free-quota candidates | REVERIFY |
| AI Horde | Non-sensitive only and privacy-reviewed | REVERIFY |
| Z.ai; Mistral Free; ModelScope; NVIDIA NIM | Collected alternative providers | REVERIFY / no automatic paid route |
| OmniRoute; FreeLLMAPI; free-coding-models; free-claude-code; Bansos; 9Router | Software/router ideas, not proof of a free model entitlement | REVERIFY |
| iFlow old API; Gemini CLI OAuth proxy; LLM7; SiliconFlow | Previously rejected/dead/quarantined under $0/security rule | REJECT/QUARANTINE unless explicit new audit |
| OpenCode Zen promotion | Promotion-only; never recurring backbone | BACKUP CANDIDATE ONLY |

## Per-phase accounting gate

For each new repo/idea: record source and exact revision, useful concept, existing implementation match, duplicates, license, trust/permissions, zero-cost impact, owner layer, phase, tests and `INTEGRATED / MERGED / DEFERRED / REJECTED` reason. Reconcile every row again before final integration. Model/provider rows remain **unverified** until a live check; do not silently spend or claim that the free-router phase is already built.
