# AIRI / Plast-Mem Android source-parity matrix

Audit date: 2026-09-13

- AIRI: `moeru-ai/airi@00c6867b7fd8064938de1805814578db8273dafe`
- Previous AIRI pin: `553d8a0da4ef131441a1de77d556c6df6cab3026`
- Plast-Mem: `moeru-ai/plast-mem@611103456d953c9a74452f4239817b3468f94bba`
- FSRS dependency: `open-spaced-repetition/fsrs-rs@aca2838bfbdc6f15ca3f7a0c96a99fae466c9e9c` (5.2.0)

Status vocabulary is deliberately closed: `DIRECT PORT`, `ANDROID-EQUIVALENT
PORT`, `COMPLETED UPSTREAM WIP`, `NOT APPLICABLE`, or `BLOCKED`. A blocked row
is not counted as parity.

## AIRI

| Upstream module/file | Responsibility | LYRA Android owner | Status | Test coverage | Known difference |
|---|---|---|---|---|---|
| `packages/core-agent/src/runtime/context-registry.ts` and test | Source-owned context, replace-self, append-self | `AiriMemoryRuntime.kt` / `LyraContextRegistry` | DIRECT PORT | `AiriMemoryArchitectureTest`, `AiriPlastMemoryParityTest` | Kotlin synchronized registry; no JS event emitter |
| `packages/core-agent/src/contracts/context-port.ts` | Context port contract | `ContextEntry`, `ContextMutation` | ANDROID-EQUIVALENT PORT | context isolation tests | In-process typed API instead of TS port |
| `packages/core-agent/src/messages/types.ts` | Canonical message types | `ConversationTruthEntity` | ANDROID-EQUIVALENT PORT | ordering/persistence tests | Room row rather than TS union |
| `packages/core-agent/src/messages/projection.ts` and test | Provider-safe bounded projection | `AiriMemoryStore.promptProjection` | ANDROID-EQUIVALENT PORT | bounded projection tests | No web transport envelope |
| `packages/core-agent/src/messages/compaction.ts` and test | Compact old provider context without deleting truth | Room truth plus bounded recent projection | ANDROID-EQUIVALENT PORT | truth-vs-projection tests | No model-authored summary lane yet; projection is truncation |
| `packages/core-agent/src/messages/context-prompt.ts` | Render selected context | `LyraContextRegistry.snapshot` and Gemini session guidance | ANDROID-EQUIVALENT PORT | context tests | Android string projection |
| `packages/core-agent/src/agents/spark-command/schema.ts` | Command intents, priority, interrupt, guidance, persona, routed contexts | `SparkRuntime.kt` | DIRECT PORT | all-intent, guidance, routing tests | Native Kotlin types |
| `packages/core-agent/src/agents/spark-command/tools.ts` | Emit protocol-ready command | `LyraSparkRuntime.dispatch` | ANDROID-EQUIVALENT PORT | dispatch/stale tests | Local unified-agent queue, not WebSocket |
| `packages/core-agent/src/agents/spark-notify/agent.ts` | Evaluate notify into text, command, or no response | `LyraSparkRuntime.notify`, `UnifiedLyraAgent.handleSparkNotify` | ANDROID-EQUIVALENT PORT | decision/linkage tests | Host supplies policy; no second provider client |
| `packages/core-agent/src/agents/spark-notify/schema.ts`, `tools.ts` | Strict command/no-response tool contract | `SparkNotifyDecision`, response controls | DIRECT PORT | notify policy tests | Kotlin sealed results |
| `packages/core-agent/src/agents/spark-notify/plugins/*` | Per-turn observer/reaction plugins | trace callback and verified text reaction | ANDROID-EQUIVALENT PORT | trace/outcome tests | No browser plugin loader |
| `packages/stage-ui/src/stores/character/orchestrator/store.ts` | Notify scheduling/retry/attention | `SparkNotifyScheduler` under `UnifiedLyraAgent` | ANDROID-EQUIVALENT PORT | urgency/dedup/retry/bounds tests | Host lifecycle supplies periodic tick instead of browser timer |
| `packages/plugin-protocol/src/types/events.ts` Spark events | Wire event shape and parentage | `SparkCommand`, `SparkNotifyEvent` | ANDROID-EQUIVALENT PORT | event/parent tests | No WebSocket serialization needed internally |
| `services/computer-use-mcp/src/task-memory/types.ts` | Active task state | `WorkingTaskMemory` | DIRECT PORT | task state tests | Android fields add verified device state |
| `services/computer-use-mcp/src/task-memory/merge.ts`, `manager.ts` | Bounded merge and stale updates | `WorkingTaskMemoryStore` | DIRECT PORT | bounds/stale tests | Synchronized in-process store |
| `services/computer-use-mcp/src/transcript/store.ts`, `types.ts` | Stored transcript truth | `ConversationTruthEntity`, Room DAO | ANDROID-EQUIVALENT PORT | append/order tests | SQLite instead of filesystem blocks |
| `services/computer-use-mcp/src/transcript/projector.ts`, `compactor.ts` | Bounded task transcript | `promptProjection` | ANDROID-EQUIVALENT PORT | boundedness test | No task-block markdown format |
| `packages/pipelines-audio/src/transcript-buffer.ts` | Combine fragments; failure isolation | `FinalTranscriptTurnBuffer` | DIRECT PORT | transcript buffer tests | Android ASR lifecycle owns finalization |
| `services/computer-use-mcp/coding-plast-mem-bridge-contract.md` | AIRI ↔ long-term memory boundary | `MemoryBrainCoordinator`, `AiriMemoryStore` | ANDROID-EQUIVALENT PORT | owner/static tests | In-process Kotlin boundary |
| Telegram DB memory schema | Example working/long-term schema | normalized Room entities | ANDROID-EQUIVALENT PORT | Room/source tests | Not a Telegram deployment |
| Desktop/browser/Minecraft consumers | Platform-specific UI and game integrations | none | NOT APPLICABLE | source audit | Not Android memory runtime |
| Agent/runtime subsystem named Pulse | No such subsystem in audited source | Spark Notify only | NOT APPLICABLE | repository search | UI pulse animation is unrelated |

## Plast-Mem

| Upstream module/file | Responsibility | LYRA Android owner | Status | Test coverage | Known difference |
|---|---|---|---|---|---|
| `crates/core/src/conversation_message.rs`, `message_ingest.rs` | Ordered append-only conversation ingestion | `ConversationTruthEntity`, `captureConversation` | ANDROID-EQUIVALENT PORT | append/idempotency tests | Room/UUID string IDs |
| `crates/entities/src/conversation_message.rs` + migration 01 | Conversation schema/indexes | Room truth entity/DAO | ANDROID-EQUIVALENT PORT | schema tests | SQLite types |
| `crates/core/src/segmentation_state.rs` + migration 02 | Claim/progress/EOF state | `SegmentationStateEntity` | ANDROID-EQUIVALENT PORT | claim/recovery tests | Android lifecycle fields added |
| `crates/entities/src/episode_span.rs` + migration 03 | Immutable committed spans | `EpisodeSpanEntity` | ANDROID-EQUIVALENT PORT | idempotent span tests | Deterministic string ID |
| `crates/event_segmentation/src/event_segmenter.rs` | Embedding candidate geometry, boundary budget, model review | `AiriEventSegmenter`, `GeminiMemoryReasoningProvider` | ANDROID-EQUIVALENT PORT | soft/hard/tail/model-review tests | Pinned E5 geometry after lazy initialization; hard gaps remain deterministic; oversized groups are bounded locally |
| `crates/event_segmentation/src/legacy.rs` | Temporal/primitive/resegmentation stages retained upstream for compatibility | `AiriEventSegmenter` | ANDROID-EQUIVALENT PORT | temporal/tail tests | Android uses the current candidate-review path, not upstream's legacy compatibility implementation |
| `crates/worker/src/jobs/event_segmentation.rs` | Claim, stale recovery, abort, tail, EOF, enqueue | coordinator segmentation lane | ANDROID-EQUIVALENT PORT | stale/process recovery tests | Coroutine jobs, not Apalis |
| `crates/worker/src/jobs/episode_creation.rs` | Deterministic episode, rendered content, embedding, FSRS init | `ensureEpisodeForSpan`, `AiriFsrs.initial` | ANDROID-EQUIVALENT PORT | episode/idempotency/FSRS tests | Local renderer; feature-hash fallback |
| `crates/entities/src/episodic_memory.rs` + migration 05 | Episode/FSRS/search schema | `EpisodicMemoryEntity`, FTS entity | ANDROID-EQUIVALENT PORT | Room/source tests | SQLite vector encoding |
| `crates/worker/src/jobs/predict_calibrate.rs` | Related-fact Predict/Calibrate and atomic actions | `GeminiMemoryReasoningProvider`, coordinator consolidation queue | ANDROID-EQUIVALENT PORT | cold-start, target-ID, assertion/literal, dedup tests | Existing Gemini provider/key is used through a bounded background adapter; Android additionally enforces safety and current-source literals |
| `crates/entities/src/semantic_memory.rs` + migration 06 | Semantic fact lifecycle/provenance | `SemanticMemoryEntity`, `SemanticProvenanceEntity` | ANDROID-EQUIVALENT PORT | lifecycle/provenance tests | SQLite schema plus stable entity index |
| `crates/core/src/memory/semantic.rs` | BM25/vector/RRF active semantic retrieval | Room FTS + E5 vector lane + `ReciprocalRankFusion` | ANDROID-EQUIVALENT PORT | RRF/retrieval/version tests | Neural model is lazily downloaded and verified; structured/FTS recall remains available offline |
| `crates/core/src/memory/episodic.rs` | BM25/vector/RRF plus FSRS rerank | `hybridRetrieve` | ANDROID-EQUIVALENT PORT | retrieval/FSRS tests | Native Room candidates and locally encoded vectors replace pgvector SQL |
| `crates/core/src/memory/retrieval.rs` | Bounded result rendering | `MemoryEntity` projection/formatter | ANDROID-EQUIVALENT PORT | bounded recall tests | Native response formatter |
| `crates/ai/src/embed*.rs`, cosine | Configured/versioned semantic embedding provider | `AndroidE5EmbeddingProvider`, model/version/dimension metadata, bounded re-embedding | ANDROID-EQUIVALENT PORT | codec/version/fallback/tokenizer tests | MIT multilingual-e5-small is pinned, checksum-verified, and cached in app-private storage; feature hash is an explicitly labelled warm-up/offline fallback |
| `crates/core/src/pending_review_queue.rs` + migration 04 | Retrieval review side effect | `PendingReviewEntity` | ANDROID-EQUIVALENT PORT | review enqueue tests | SQLite queue |
| `crates/worker/src/jobs/memory_review.rs` | Aggregate/review/rate/update | owner review queue + Gemini ratings | ANDROID-EQUIVALENT PORT | exact state/queue tests | Durable Room queue and startup recovery replace Apalis; partial ratings remain pending |
| `fsrs` 5.2.0 dependency | FSRS-6 inference | `AiriFsrs` | DIRECT PORT | pinned numeric conformance tests | Training APIs are not needed on device |
| `crates/server/src/api/*` | HTTP API surface | owner Kotlin API | NOT APPLICABLE | service boundary tests | No localhost server required |
| `crates/migration/*` | PostgreSQL schema migration | Room v10 destructive pre-release cutover | ANDROID-EQUIVALENT PORT | schema/static tests | Clean reinstall required |
| `docs/todo/flashbulb_memory.md` | Documented high-significance TODO | `FlashbulbPolicy`, episode fields | COMPLETED UPSTREAM WIP | policy tests | LYRA extension, not upstream production parity |
| `docs/todo/semantic_memory_confidence.md` | Proposed semantic confidence evolution | explicit/inferred confidence metadata | COMPLETED UPSTREAM WIP | behavior/safety tests | Conservative LYRA policy |
| `docs/architecture/graph_memory.md` | Graph direction | stable people/aliases/relationships | ANDROID-EQUIVALENT PORT | entity tests | LYRA-specific assistant identity graph |
| PostgreSQL/pgvector server and benchmark API | Server deployment/benchmarking | none | NOT APPLICABLE | source audit | Android uses local Room; no external server |

## Completion truth

All applicable runtime rows are directly ported or represented by a documented
Android equivalent. Server deployment APIs remain not applicable. The neural
lane intentionally degrades to labelled structured/FTS/feature-hash retrieval
until the pinned model is available; this degraded mode is not described as
neural parity. The production system remains one owner, one Room truth, one
fast local recall path, and contains no JARVIS or Memory V2 fallback.
