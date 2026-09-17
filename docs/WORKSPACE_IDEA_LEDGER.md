# LYRA Workspace — repo/idea and free-route ledger

Updated 2026-09-17. This is an **accounting register**, not a declaration that every upstream repo, model or feature was installed or freshly audited. Before each implementation slice, inspect current upstream source, existing LYRA code, license, permissions, cost and regression impact. One LYRA Core; no duplicate planner, memory owner, model router or unreviewed code copying.

Status key: `INTEGRATED` = demonstrably in LYRA; `BORROW` = concept to implement in the stated phase; `REVIEW` = re-audit before a decision; `REJECT` = do not integrate under current constraints. A row can contain deferred ideas; no silent dropping. Any newly supplied repo/provider gets a row before use.

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
| Shubhamsaboo/awesome-llm-apps | Scope-creep checks, one-mutation improvement, skill admission/security/evals, provider-specific cost audit | 5, 14–18 | BORROW |
| browser-use | Real browser worker, observe/action/verify and permission boundaries | 13 | BORROW |
| Panniantong/Agent-Reach | Read-only public-source adapters, health check != execution, safe CDP/login, tiered URL reader | 13 | BORROW |
| abi/screenshot-to-code | Screenshot → semantic UI structure → editable code → preview → visual refinement | 8 | BORROW |
| nextlevelbuilder/ui-ux-pro-max-skill | Searchable design systems, project MASTER/page overrides, stack-specific accessibility/responsive QA | 7–9 | BORROW |
| Leonxlnx/taste-skill | Design taste, art direction, anti-generic patterns, screenshot → redesign, visual polish | 7–9 | BORROW |
| Graft | Project structure/impact and bounded code context rather than whole-repo dumping | 10 | BORROW; recheck exact repo |
| codebase-memory-mcp | Project-specific code knowledge, explicit provenance and scope | 10 | BORROW; recheck exact repo |
| context-mode | Bounded prompt projection and stable coding context | 3, 5, 10 | BORROW; recheck exact repo |
| code-review-graph | Tree-sitter graph, diff/blast radius, affected tests, bounded review; not compiler authority | 10–11, 15 | BORROW |
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
| user CodeAI recording / SPCK workflow | Top tabs, safe file edits, task chat/preview loop; no full UI copy | 3–6 | INTEGRATED editor ideas; BORROW chat loop, prompt handoff MERGED in Slice 14 |

## Collected free model/provider *candidates*, NOT current free certifications

Every provider must be verified at the **exact route/model/account tier** immediately before use: recurring $0 limit, no credit card, no AutoPay, no paid fallback, privacy/data use, quota reset, tool calling, context/output limits, health, rate-limit retry and task checkpoint. A free model's name does not make every hosting route free.

| Provider / router candidates | Previously collected routes or ideas | Current disposition |
|---|---|---|
| Requesty; Groq Free; Cloudflare Workers AI | Limited recurring allowances; GPT-OSS/Qwen/Compound; Workers AI daily neuron allowance | REVERIFY exact limits/routes |
| Gemini Free | Free-tier route; privacy gate | REVERIFY, gated |
| OpenRouter Free Pool | Exact `:free` routes only; dynamic availability | REVERIFY exact route |
| Ollama local / Cloud Starter | Local or starter capability subject to device/cloud constraints | REVERIFY |
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
