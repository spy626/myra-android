# AIRI memory architecture attribution

LYRA's native memory runtime ports applicable architecture contracts from
[moeru-ai/airi](https://github.com/moeru-ai/airi), inspected at revision
`553d8a0da4ef131441a1de77d556c6df6cab3026`, and from
[moeru-ai/plast-mem](https://github.com/moeru-ai/plast-mem), inspected at
revision `611103456d953c9a74452f4239817b3468f94bba`.

The source-owned context registry, bounded/stale task-memory merge, transcript
truth/projection, transcript buffering, Spark Command, and Spark Notify
responsibilities are derived from AIRI's MIT-licensed project. Stateful event
segmentation, episodic memory, semantic NEW/REINFORCE/UPDATE/INVALIDATE,
BM25/vector/RRF retrieval, and episodic FSRS review follow Plast-Mem's current
architecture and contracts. Kotlin, Room, local feature-hash embeddings, the
stable person/entity index, Android lifecycle integration, and stronger LYRA
secret policy are Android-native equivalents.

At these revisions, Flashbulb Memory remains an upstream documented TODO.
LYRA's guarded high-significance episode flag and retrieval floor are an
Android completion of that TODO, not a claim of upstream production support.
No agent/runtime subsystem named Pulse exists in the inspected AIRI source;
Spark Notify is therefore the ported proactive event lane and no synthetic
"AIRI Pulse" subsystem was introduced.

MIT License

Copyright (c) 2024-PRESENT Neko Ayaka

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
