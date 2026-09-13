# LYRA AIRI/Plast-Mem memory runtime

```mermaid
flowchart TD
    T[Final authoritative user turn] --> I[Immediate lane]
    T --> B[Background memory lane]
    I --> S[Safety and intent ownership]
    S --> R[Local recall / verified explicit write / normal reply]
    R --> V[Verified response]
    B --> C[Append-only conversation truth]
    C --> G[Stateful temporal + model segmentation]
    G --> E[Episodic memory]
    E --> P[Relevant-fact Predict / Calibrate]
    P --> A[Grounding and LYRA safety]
    A --> O[MemoryBrainCoordinator]
    O --> D[One Room transaction]
    D --> X[Semantic + relationship + goal projections]
    X --> H[FTS + compatible E5 vectors + RRF]
    H --> F[Later retrieval + pending review + FSRS]
```

The immediate lane never waits for segmentation, Predict/Calibrate, neural
model download, reindexing, or episodic review. Clear structured recall is
read-only and local. The background lane uses Room episode and pending-review
state as its durable queue truth; bounded coroutine channels only accelerate
execution.

The source-owned Context Registry and Task Memory contribute bounded current
state to LYRA Core. Spark Notify supplies proactive events and may produce no
response, a verified text reaction, or a Spark Command. Spark Command routes
structured work to existing phone/browser/screen/search/memory lanes but cannot
bypass memory authorization or Room ownership.
