# AIRI memory architecture attribution

LYRA's native memory runtime ports applicable architecture contracts from
[moeru-ai/airi](https://github.com/moeru-ai/airi), inspected at revision
`dfc6951a55bf4fdd66a68a0c881ae880ddd95f77`.

The source-owned context registry, bounded/stale task-memory merge, transcript
truth/projection, and serialized transcript-buffer responsibilities are derived
from AIRI's MIT-licensed project. The Android Room consolidation, entity graph,
local retrieval, safety, and review/decay implementation is an independent
native completion of AIRI's plast-mem contract/WIP seam.

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
