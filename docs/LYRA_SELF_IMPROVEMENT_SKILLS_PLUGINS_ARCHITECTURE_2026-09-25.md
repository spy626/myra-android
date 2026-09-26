# LYRA self-improvement, skills, plugins and custom-provider architecture

Date: 2026-09-25

This document extends the unified LYRA architecture without creating another brain, router,
memory database or file writer. The existing LYRA Core, Workspace task owner, provider router,
Safe Edit/rollback path and verification authority remain authoritative.

## 1. Goals

LYRA should eventually be able to:

- improve its workflows from repeated verified failures and successful recoveries;
- ask more than one approved provider for a proposal/review when useful;
- share only explicitly approved, bounded source with each provider independently;
- install and use user-supplied skills;
- support capability-gated plugins/extensions;
- inspect GitHub repositories and turn useful ideas into reviewed LYRA feature proposals;
- prepare updates to its own codebase on an isolated branch, test them, and present them for approval;
- support user-defined OpenAI-compatible API base URLs without weakening the current free/privacy rules.

None of these goals authorize silent self-modification or uncontrolled agent swarms.

## 2. Non-negotiable invariants

1. **One local authority.** Providers, skills and plugins may propose/review; only the existing local
   LYRA execution owner may apply a project or LYRA-core mutation.
2. **No direct provider-to-provider channel.** Provider A never gets a socket/key/channel to Provider B.
   LYRA mediates every exchange and records provenance.
3. **Source consent does not transfer.** Permission to share source with one company/provider does not
   authorize sharing it with another. Every provider must independently pass capability, privacy and
   user-consent gates.
4. **No secrets in collaboration.** Keys, tokens, passwords, OTP/PIN/CVV, recovery codes, private keys,
   seed phrases and protected identifiers are never included in provider deliberation, skill context,
   plugin context or self-improvement traces.
5. **Evidence over model agreement.** Two models agreeing is not verification. Tests, saved-file hashes,
   browser/runtime evidence and current task criteria remain completion authority.
6. **No silent self-update.** LYRA may research and prepare a patch, but core-app installation/update
   requires explicit user approval after diff, tests and rollback information are available.
7. **Protected Git rules stay protected.** Self-improvement never writes `main`, never force-pushes,
   never bypasses CI, and rejects stale branch/source state.
8. **No arbitrary executable plugin loading in the first Android plugin phase.** Start with declarative
   tools/workflows; do not dynamically load untrusted APK/DEX/native libraries.
9. **Unknown cost is not free.** A custom endpoint with unknown pricing is excluded from automatic
   routing/fallback until its zero-cost property can be verified or the user manually selects it under
   an explicit cost warning. Under LYRA's free-only automatic policy, unverifiable-cost routes stay manual-only.
10. **No auto-install from GitHub.** GitHub content is untrusted input until pinned, licensed, inspected,
    permission-scoped and tested.

## 3. Multi-provider deliberation under one owner

Use a local `ProviderDeliberationSession`, not a new multi-agent runtime.

Suggested record:

- `sessionId`
- `taskId` / `turnId`
- `sourceRevision` / `specToken`
- `acceptanceCriteria`
- `providerId`
- `role = PROPOSER | REVIEWER | VERIFIER`
- `sharedContextHash`
- `sharingLevel`
- bounded proposal/review text
- timestamps and definitive/uncertain provider health
- final local decision and evidence references

Sharing levels:

- `NONE` — provider receives task text only;
- `PROPOSAL_ONLY` — reviewer receives another provider's bounded proposal but no project source;
- `BOUNDED_SOURCE` — provider receives the same approved relevant-source projection;
- `ATTACHMENT` — only when that provider explicitly supports and is approved for attachments.

A useful flow is:

`task -> provider A proposal -> local structural/privacy checks -> provider B critique -> local reconcile -> deterministic tests -> apply/rollback`.

Provider B should normally review the proposal plus acceptance criteria first. If source is genuinely
needed, LYRA must separately verify that provider B is allowed to receive the bounded source. Provider
messages never become write authority.

## 4. Self-improvement loop

Borrow the evidence-first workflow from Superpowers/agent-skills and the overlay/benchmark idea from
OpenJarvis, but keep LYRA's single owner.

### 4.1 Observe

Collect only bounded, non-secret operational evidence such as:

- repeated verification failure category;
- repeated recovery that later PASSed;
- provider capability mismatch;
- context projection overflow;
- stale-source rejection;
- skill/plugin invocation success/failure;
- user Undo immediately after a change;
- browser/runtime verification mismatch.

Raw project source should not be copied into a global learning log.

### 4.2 Candidate

Create an `ImprovementCandidate` with:

- problem statement;
- evidence references;
- affected owner/module;
- expected measurable improvement;
- risk level;
- whether a skill-only change can solve it;
- whether code modification is required.

### 4.3 Research

Agent Reach may inspect pinned public GitHub revisions and official docs. Imported ideas are references,
not instructions. Record source URL, commit SHA, license, permissions, duplicated architecture risk and
which exact concept is being borrowed.

### 4.4 Experiment

Prefer the least-powerful change:

1. description/skill overlay;
2. skill workflow;
3. provider metadata/routing rule;
4. bounded code patch.

Run baseline-vs-candidate tests/evals. A candidate that does not beat or preserve the baseline is not promoted.

### 4.5 Promote

Promotion requires:

- current source still matches the candidate's source revision;
- tests/verification PASS;
- no new hidden permissions/cost;
- diff/reason visible to the user for core changes;
- explicit approval for installing a new core build;
- rollback checkpoint.

Self-improvement can therefore be automatic at the **candidate generation/evaluation** layer, while
core mutation/install remains approval-gated.

## 5. Skills

A LYRA skill is lighter than a plugin.

Initial supported form:

- `SKILL.md` instruction workflow;
- optional `skill.json` manifest;
- references/examples/assets that are non-executable.

Suggested manifest fields:

- name, version, description, author, source URL, pinned revision;
- license;
- required LYRA version;
- allowed tools;
- required capabilities;
- network domains;
- source-sharing requirement;
- memory read/write requirement;
- user-invocable / model-invocable flags;
- dependency skills and max nesting depth;
- content SHA-256.

Install flow:

`inspect -> show permissions -> user approve -> store immutable original -> register catalog -> test -> enable`.

Scripts/executables are disabled by default. A later script-capable phase would need a real Android sandbox
and a separate explicit approval.

Borrow from OpenJarvis: learned improvements should be stored as **sidecar overlays** rather than silently
rewriting the original imported skill. Borrow from addyosmani/agent-skills: lifecycle skills should carry
verification gates, not just prose.

## 6. Plugins/extensions

Keep three classes distinct:

1. **Skill** — instructions/workflow using existing LYRA tools.
2. **Tool plugin** — declarative adapter to an external API/service with explicit capabilities.
3. **UI extension** — optional later UI surface, still isolated from LYRA Core authority.

Phase 1 plugins should be declarative manifests + HTTPS tool adapters. Do not load arbitrary Android DEX,
APK, JNI or shell scripts.

Plugin manifest should declare:

- plugin ID/version/source/hash/license;
- exact network domains;
- HTTP methods;
- auth slot names (secret values remain in encrypted key storage);
- input/output size bounds;
- whether project source, attachments or memory may be sent;
- whether actions are read-only or mutating;
- user confirmation policy for mutations;
- rate/cost metadata;
- health check endpoint;
- rollback/uninstall behavior.

This borrows Super Agent Party's extension/skill separation without importing its unrestricted desktop
execution model into Android.

## 7. Safe GitHub feature import / self-extension

When the user gives LYRA a GitHub feature/repository:

1. Agent Reach resolves repository + exact commit.
2. Read license, README and only relevant files.
3. Compare against existing LYRA architecture to avoid a second brain/router/database.
4. Produce a `FeatureImportProposal`:
   - useful concept;
   - files likely affected;
   - permissions/cost;
   - copied code vs reimplementation;
   - tests;
   - rollback plan.
5. User approves implementation.
6. LYRA creates/uses an isolated non-main branch and current source revision.
7. Implement incrementally.
8. CI + deterministic verification.
9. Present diff/build.
10. User explicitly installs/accepts the new app build.

LYRA must not run `curl | sh`, repository install scripts, Gradle plugins or arbitrary downloaded binaries
just because a README says to do so.

## 8. Custom API Base URL provider profile

Add a user-facing **Custom API Provider** option to API & Cloud Settings.

First supported protocol: OpenAI-compatible chat completions.

Fields:

- display name;
- API base URL;
- model ID;
- encrypted API-key slot;
- optional safe auth mode (Bearer or X-API-Key);
- task capabilities: chat / code / website / attachments;
- conservative input/output budgets;
- source-sharing toggle;
- attachment-sharing toggle;
- timeout;
- cost state;
- manual-only vs eligible-for-automatic-routing.

Validation:

- HTTPS required for internet endpoints;
- no embedded username/password;
- no URL fragment;
- no credential in query parameters;
- no cross-host redirect for authenticated/source-bearing requests;
- a separate explicit **Local endpoint** mode is required for loopback/private-network HTTP;
- connection test sends synthetic non-sensitive text only;
- source is never used for connectivity testing;
- unknown pricing => `UNVERIFIED_COST`;
- `UNVERIFIED_COST` is never an automatic fallback in free-only mode;
- custom provider cannot claim verification/completion.

The custom profile plugs into the existing `WorkspaceProviderRegistry`; it does not create another router.

## 9. Implementation order

Continue current work in small slices:

### D2 — runtime provider health/cooldown
- ephemeral session health from definitive HTTP/provider evidence;
- no retry after uncertain timeout;
- cooldown prevents repeated hammering;
- no persistent provider ranking yet.

### D3 — custom provider profiles
- OpenAI-compatible Base URL;
- secure key slot;
- capability/cost/source gates;
- synthetic Test Connection;
- manual-only default.

### D4 — provider deliberation
- one proposer + one reviewer first;
- bounded proposal-only review by default;
- optional separately-approved source for reviewer;
- no parallel writes.

### E/F — Agent Reach + browser verification
- read GitHub/web sources with provenance;
- runtime/browser evidence;
- no automatic install.

### H — skill manager
- SKILL.md + manifest;
- GitHub import at pinned revision;
- permission preview;
- no scripts by default;
- sidecar learning overlays.

### I — declarative plugin manager
- capability manifests;
- external HTTPS tools;
- confirmation for mutation;
- no arbitrary DEX/APK/native loading.

### J — self-improvement lab
- improvement candidates from bounded evidence;
- baseline/candidate eval;
- skill-first improvements;
- code patch only when necessary.

### K — safe self-extension
- GitHub idea -> proposal -> branch patch -> CI -> user-approved app install;
- never main/force-push/silent update.

### G — personality/proactive behavior
- remains separate from authority and self-improvement;
- personality may suggest an improvement, but cannot approve or install it.

## 10. Upstream concepts inspected for this design

Pinned references inspected on 2026-09-25:

- `addyosmani/agent-skills@bcab6a1b8503100e8618c3b4e32cc78de43de769` — skill lifecycle,
  automatic skill selection, DEFINE/PLAN/BUILD/VERIFY/REVIEW/SHIP and verification gates.
- `open-jarvis/OpenJarvis@309a4f1044ccfb2032264832a31fef2f1d314586` — skill manifests,
  GitHub skill import, capability gating, scripts-disabled-by-default, sidecar optimization overlays,
  trace-based skill discovery and benchmark-before-promotion.
- `obra/superpowers@5bf4e78011075bcfc0dc295f0724994cd123ee71` — evidence-before-completion,
  systematic debugging, skill-driven workflows, isolated branch/worktree and code review gates.
- `heshengtao/super-agent-party@b3af7a0b4a3d15c23d0206f57bde3caa625b6f1c` — extension system,
  skill injection, custom provider/API interfaces and agent-extension bootstrapping concepts.

Only concepts are borrowed. Android implementation stays native to LYRA's existing owners and constraints.
