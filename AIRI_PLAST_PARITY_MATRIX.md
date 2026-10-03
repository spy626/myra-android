# AIRI / Plast-Mem Android source-parity matrix

Audit date: 2026-09-14

- AIRI: `moeru-ai/airi@1a79f8b1ca11414039843a60e0e7ea1c526b4b3f`
- Previous AIRI pin: `9f30a1977e09b3d68759492c5f8f775eb4502184`
- Plast-Mem: `moeru-ai/plast-mem@611103456d953c9a74452f4239817b3468f94bba`
- FSRS dependency: `open-spaced-repetition/fsrs-rs@aca2838bfbdc6f15ca3f7a0c96a99fae466c9e9c` (5.2.0)

Status vocabulary is deliberately closed: `EXACT PORT`, `ANDROID-NATIVE
EQUIVALENT`, `UPSTREAM TODO`, `INTENTIONAL LYRA SAFETY EXTENSION`, or `NOT
APPLICABLE`. There are no unresolved applicable runtime rows in this revision.

## AIRI

| Upstream module/file | Responsibility | LYRA Android owner | Status | Test coverage | Known difference |
|---|---|---|---|---|---|
| `packages/core-agent/src/runtime/context-registry.ts` and test | Source-owned context, replace-self, append-self | `AiriMemoryRuntime.kt` / `LyraContextRegistry` | EXACT PORT | `AiriMemoryArchitectureTest`, `AiriPlastMemoryParityTest` | Kotlin synchronized registry; no JS event emitter |
| `packages/core-agent/src/contracts/context-port.ts` | Context port contract | `ContextEntry`, `ContextMutation` | ANDROID-NATIVE EQUIVALENT | context isolation tests | In-process typed API instead of TS port |
| `packages/core-agent/src/messages/types.ts` | Canonical message types | `ConversationTruthEntity` | ANDROID-NATIVE EQUIVALENT | ordering/persistence tests | Room row rather than TS union |
| `packages/core-agent/src/messages/projection.ts` and test | Provider-safe bounded projection | `AiriMemoryStore.promptProjection` | ANDROID-NATIVE EQUIVALENT | bounded projection tests | No web transport envelope |
| `packages/core-agent/src/messages/compaction.ts` and test | Default count-summary plus recent explicit history without deleting truth | `ConversationProjection` plus Room truth | ANDROID-NATIVE EQUIVALENT | truth-vs-projection tests | Uses AIRI's deterministic default summary contract; no optional domain summarizer |
| `packages/core-agent/src/messages/context-prompt.ts` | Render selected context | `LyraContextRegistry.snapshot` and Gemini session guidance | ANDROID-NATIVE EQUIVALENT | context tests | Android string projection |
| `packages/core-agent/src/agents/spark-command/schema.ts` | Command intents, priority, interrupt, guidance, persona, routed contexts | `SparkRuntime.kt` | EXACT PORT | all-intent, guidance, routing tests | Native Kotlin types |
| `packages/core-agent/src/agents/spark-command/tools.ts` | Emit protocol-ready command | `LyraSparkRuntime.dispatch` | ANDROID-NATIVE EQUIVALENT | dispatch/stale tests | Local unified-agent queue, not WebSocket |
| `packages/core-agent/src/agents/spark-notify/agent.ts` | Evaluate notify into text, command, or no response | `LyraSparkRuntime.notify`, `UnifiedLyraAgent.handleSparkNotify` | ANDROID-NATIVE EQUIVALENT | decision/linkage tests | Host supplies policy; no second provider client |
| `packages/core-agent/src/agents/spark-notify/schema.ts`, `tools.ts` | Strict command/no-response tool contract | `SparkNotifyDecision`, response controls | EXACT PORT | notify policy tests | Kotlin sealed results |
| `packages/core-agent/src/agents/spark-notify/plugins/*` | Per-turn observer/reaction plugins | trace callback and verified text reaction | ANDROID-NATIVE EQUIVALENT | trace/outcome tests | No browser plugin loader |
| `packages/stage-ui/src/stores/character/orchestrator/store.ts` | Notify scheduling/retry/attention | `SparkNotifyScheduler` under `UnifiedLyraAgent` | ANDROID-NATIVE EQUIVALENT | urgency/dedup/retry/bounds tests | Host lifecycle supplies periodic tick instead of browser timer |
| `packages/plugin-protocol/src/types/events.ts` Spark events | Wire event shape and parentage | `SparkCommand`, `SparkNotifyEvent` | ANDROID-NATIVE EQUIVALENT | event/parent tests | No WebSocket serialization needed internally |
| `services/computer-use-mcp/src/task-memory/types.ts` | Active task state | `WorkingTaskMemory` | EXACT PORT | task state tests | Android fields add verified device state |
| `services/computer-use-mcp/src/task-memory/merge.ts`, `manager.ts` | Bounded merge and stale updates | `WorkingTaskMemoryStore` | EXACT PORT | bounds/stale tests | Synchronized in-process store |
| `services/computer-use-mcp/src/transcript/store.ts`, `types.ts` | Stored transcript truth | `ConversationTruthEntity`, Room DAO | ANDROID-NATIVE EQUIVALENT | append/order tests | SQLite instead of filesystem blocks |
| `services/computer-use-mcp/src/transcript/projector.ts`, `compactor.ts` | Bounded task transcript | `promptProjection` | ANDROID-NATIVE EQUIVALENT | boundedness test | No task-block markdown format |
| `packages/pipelines-audio/src/transcript-buffer.ts` | Combine fragments; failure isolation | `FinalTranscriptTurnBuffer` | EXACT PORT | transcript buffer tests | Android ASR lifecycle owns finalization |
| `services/computer-use-mcp/coding-plast-mem-bridge-contract.md` | AIRI ↔ long-term memory boundary | `MemoryBrainCoordinator`, `AiriMemoryStore` | ANDROID-NATIVE EQUIVALENT | owner/static tests | In-process Kotlin boundary |
| Telegram DB memory schema | Example working/long-term schema | normalized Room entities | ANDROID-NATIVE EQUIVALENT | Room/source tests | Not a Telegram deployment |

## Plast-Mem

| Upstream module/file | Responsibility | LYRA Android owner | Status | Test coverage | Known difference |
|---|---|---|---|---|---|
| `crates/core/src/conversation_message.rs`, `message_ingest.rs` | Ordered append-only conversation ingestion | `ConversationTruthEntity`, `captureConversation` | ANDROID-NATIVE EQUIVALENT | append/idempotency tests | Room/UUID string IDs |
| `crates/entities/src/conversation_message.rs` + migration 01 | Conversation schema/indexes | Room truth entity/DAO | ANDROID-NATIVE EQUIVALENT | schema tests | SQLite types |
| `crates/core/src/segmentation_state.rs` + migration 02 | Claim/progress/EOF state | `SegmentationStateEntity` | ANDROID-NATIVE EQUIVALENT | claim/recovery tests | Android lifecycle fields added |
| `crates/entities/src/episode_span.rs` + migration 03 | Immutable committed spans | `EpisodeSpanEntity` | ANDROID-NATIVE EQUIVALENT | idempotent span tests | Deterministic string ID |
| `crates/event_segmentation/src/event_segmenter.rs` | Source exists but is not invoked by the current production worker | `AiriEventSegmenter` test/reference utility only | ANDROID-NATIVE EQUIVALENT | candidate-geometry tests | Explicitly not presented as the active production path |
| `crates/event_segmentation/src/legacy.rs` | **Actively invoked:** temporal rules → primitive classify/split → soft-boundary constrained resegmentation | `AiriActiveSegmentationPipeline`, `GeminiMemoryReasoningProvider` | ANDROID-NATIVE EQUIVALENT | small/medium/long/soft/hard/carry tests | Same 4/20/30-message and 30m/>3h contracts; Room/coroutines replace worker transport |
| `crates/worker/src/jobs/event_segmentation.rs` | **Actively invoked:** claim validation, active legacy pipeline, commit/abort/re-enqueue | coordinator background segmentation lane | ANDROID-NATIVE EQUIVALENT | stale/process/model-failure/carry tests | Room is durable state; bounded coroutine jobs replace Apalis |
| `crates/worker/src/jobs/episode_creation.rs` | Deterministic episode, rendered content, embedding, FSRS init | `ensureEpisodeForSpan`, `AiriFsrs.initial` | ANDROID-NATIVE EQUIVALENT | episode/idempotency/FSRS tests | Local renderer; feature-hash fallback |
| `crates/entities/src/episodic_memory.rs` + migration 05 | Episode/FSRS/search schema | `EpisodicMemoryEntity`, FTS entity | ANDROID-NATIVE EQUIVALENT | Room/source tests | SQLite vector encoding |
| `crates/worker/src/jobs/predict_calibrate.rs` | **Actively invoked:** hybrid relevant-fact load, Predict/Calibrate, action normalization, atomic apply | `GeminiMemoryReasoningProvider`, coordinator consolidation queue | ANDROID-NATIVE EQUIVALENT | relevance, cold-start, target-ID, assertion/literal, source-provenance, dedup tests | Every action requires user-message sequence plus grounded span; missing UPDATE targets are rejected as stricter safety |
| `crates/entities/src/semantic_memory.rs` + migration 06 | Semantic fact lifecycle/provenance | `SemanticMemoryEntity`, `SemanticProvenanceEntity` | ANDROID-NATIVE EQUIVALENT | lifecycle/provenance tests | SQLite schema plus stable entity index |
| `crates/core/src/memory/semantic.rs` | BM25/vector/RRF active semantic retrieval | Room FTS + E5 vector lane + `ReciprocalRankFusion` | ANDROID-NATIVE EQUIVALENT | RRF/retrieval/version tests | Neural model is lazily downloaded and verified; structured/FTS recall remains available offline |
| `crates/core/src/memory/episodic.rs` | BM25/vector/RRF plus FSRS rerank | `hybridRetrieve` | ANDROID-NATIVE EQUIVALENT | retrieval/FSRS tests | Native Room candidates and locally encoded vectors replace pgvector SQL |
| `crates/core/src/memory/retrieval.rs` | Bounded result rendering | `MemoryEntity` projection/formatter | ANDROID-NATIVE EQUIVALENT | bounded recall tests | Native response formatter |
| `crates/ai/src/embed*.rs`, cosine | Configured/versioned semantic embedding provider | `AndroidE5EmbeddingProvider`, immutable `EmbeddingResult` provenance, bounded re-embedding | ANDROID-NATIVE EQUIVALENT | codec/version/fallback/tokenizer/atomic-readiness tests | MIT multilingual-e5-small is pinned, checksum-verified, and cached in app-private storage; hash fallback cannot overwrite or relabel E5 rows |
| `crates/core/src/pending_review_queue.rs` + migration 04 | Retrieval review side effect | `PendingReviewEntity` | ANDROID-NATIVE EQUIVALENT | review enqueue tests | SQLite queue |
| `crates/worker/src/jobs/memory_review.rs` | Aggregate/review/rate/update | owner review queue + Gemini ratings + leased durable work tokens | ANDROID-NATIVE EQUIVALENT | fresh/expired lease, recreation, review/reindex/consolidation replay tests | RUNNING claims are bounded leases; expired claims recover atomically, fresh claims cannot be stolen, and WorkManager awaits the owner before completion. APPEND_OR_REPLACE plus earliest pending/lease-expiry wake replaces Apalis without polling |
| `fsrs` 5.2.0 dependency | FSRS-6 inference | `AiriFsrs` | EXACT PORT | pinned numeric conformance tests | Training APIs are not needed on device |
| `crates/migration/*` | PostgreSQL schema migration | Room v12 destructive pre-release cutover; goal projection links canonical semantic lifecycle | ANDROID-NATIVE EQUIVALENT | schema/static tests | Clean reinstall required |
| `docs/todo/flashbulb_memory.md` | Documented high-significance TODO, not production-invoked upstream | `FlashbulbPolicy`, episode fields | UPSTREAM TODO | policy tests | LYRA completion of upstream documented TODO |
| `docs/todo/semantic_memory_confidence.md` | Proposed confidence evolution, not production-invoked upstream | explicit/inferred confidence metadata | UPSTREAM TODO | behavior/safety tests | LYRA completion uses conservative policy |
| `docs/architecture/graph_memory.md` | Graph direction | stable people/aliases/relationships | ANDROID-NATIVE EQUIVALENT | entity/lifecycle and multilingual semantic-contract tests | Canonical target identity owns REINFORCE/INVALIDATE. NEW/UPDATE strength comes from the single source-grounded `semantic_relationship` authority; duplicate model operation enums are ignored and no production vocabulary parser remains |

## Completion truth

All applicable runtime rows are directly ported or represented by a documented
Android equivalent. Server deployment APIs remain not applicable. The neural
lane intentionally degrades to labelled structured/FTS/feature-hash retrieval
until the pinned model is available; this degraded mode is not described as
neural parity. The production system remains one owner, one Room truth, one
fast local recall path, and contains no JARVIS or Memory V2 fallback. Every
durable consolidation execution acquires the same Room token/lease; local and
WorkManager wakes cannot execute Predict/Calibrate concurrently for one episode.
