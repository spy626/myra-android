# AIRI memory architecture attribution

LYRA's native memory runtime ports applicable architecture contracts from
[moeru-ai/airi](https://github.com/moeru-ai/airi), inspected from a complete
current checkout at revision `00c6867b7fd8064938de1805814578db8273dafe`
(superseding the previous `553d8a0da4ef131441a1de77d556c6df6cab3026`
pin), and from
[moeru-ai/plast-mem](https://github.com/moeru-ai/plast-mem), inspected at
revision `611103456d953c9a74452f4239817b3468f94bba`.

The source-owned context registry, bounded/stale task-memory merge, transcript
truth/projection, transcript buffering, Spark Command, and Spark Notify
responsibilities are derived from AIRI's MIT-licensed project. Stateful
multi-turn event segmentation, episodic memory, semantic
NEW/REINFORCE/UPDATE/INVALIDATE, BM25/vector/RRF retrieval, and episodic FSRS
review follow Plast-Mem's current architecture and contracts. Kotlin, Room,
the local ONNX Runtime lane using the MIT-licensed pinned
`intfloat/multilingual-e5-small@614241f622f53c4eeff9890bdc4f31cfecc418b3`
(384 dimensions), the stable person/entity index, Android lifecycle
integration, and stronger LYRA secret policy are Android-native equivalents.
Model and tokenizer downloads are length/SHA-256 verified and cached only in
app-private storage. The feature-hash lane is an explicitly labelled
warm-up/offline fallback and is not represented as neural parity while active.

Plast-Mem's provider-agnostic model boundary is completed on Android by a
bounded background reasoning adapter using LYRA's existing Gemini key and
provider. It performs boundary review, episode Predict/Calibrate, and episodic
review ratings without joining the voice-response session or acquiring
response ownership. Android remains the sole authorization, transaction,
provenance, stale-update, safety, and verification owner.

At the audited Plast-Mem revision, the production EventSegmentationJob still
invokes the temporal, primitive-review, and informative-resegmentation
functions located in `crates/event_segmentation/src/legacy.rs`. LYRA therefore
uses that active 4/20/30-message model-review flow; the separate embedding
candidate segmenter is not represented as the production upstream path. E5 is
used for semantic/episodic retrieval and relevant-fact selection, not to
replace the active upstream segmentation reasoning contract.

See `AIRI_MEMORY_ARCHITECTURE.md` for the immediate/background lane split. In
particular, segmentation and Predict/Calibrate never gate the Live response.

At these revisions, Flashbulb Memory remains an upstream documented TODO.
LYRA's guarded high-significance episode flag and retrieval floor are an
Android completion of that TODO, not a claim of upstream production support.
No agent/runtime subsystem named Pulse exists in the inspected AIRI source;
Spark Notify is therefore the ported proactive event lane and no synthetic
"AIRI Pulse" subsystem was introduced.

The episodic review equations and default FSRS-6 parameter set are ported from
`open-spaced-repetition/fsrs-rs` 5.2.0 (`aca2838bfbdc6f15ca3f7a0c96a99fae466c9e9c`),
the exact dependency pinned by Plast-Mem. That project is BSD-3-Clause:

Copyright (c) 2023, Open Spaced Repetition

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation
and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
may be used to endorse or promote products derived from this software without
specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

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
