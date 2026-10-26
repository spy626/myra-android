# LYRA Video Studio — Slice 27: physically continuous original construction scenes

## Change

Previous Slice26 matched camera poses across foundation→structure and structure→facade, but *not the mesh geometry*: foundation rebar, blue footing blocks, concrete slab, survey marks and terrain jumped instantly into different structure shapes/positions.

Slice27 changes only the **structure shot's starting 3D geometry**. It creates the actual foundation-end surfaces at structure frame 1 (site soil, grass, entrance, trench, two guide lines, foundation slab, four footings and four rebars), then keyframe-morphs the ground/slab/footings/rebar to their original structural dimensions and positions by frame 26. Footing color and entrance paving update during the shot. Survey markings are retired after construction starts. Its frame 72 remains compatible with the existing untouched facade scene. This is original real Blender animation, not a video cross-fade or repeated frames.

## Proof

New Blender verifier **reads editable .blend meshes**, not only JSON manifests. Compares all visible objects at two cuts using independent scene files: **15 matched objects** between foundation frame 72 and structure frame 1, plus **23 matched objects** between structure frame 72 and facade frame 1. Checks every visible mesh inventory, world location, dimensions and material RGBA within a strict numeric tolerance; extra/missing/nonfinite objects fail closed. Separate unit/adversarial tests cover transformations, mismatches and fake geometry.

In addition to physical geometry, all inherited Slice26 camera endpoint, 4-cut decoded RGB continuity, Slice25's all-360-frame pixel provenance, Python and Node security tests, and Android APK CI are retained.

## Safety and limitations

- Only the existing strictly validated offline construction rendering inputs are accepted.
- Existing foundation, facade, lot, reveal, assembly, Video Studio Android, LYRA memory/chat, production security and Cloudflare deployment settings unchanged.
- Does not dispatch production workflows, publish video from Android or introduce a paid API.
- Renderer proof is a technical stylized 360x640 12fps H264 30-second mini-house, not cinematic realism or arbitrary text-to-video.
- Human visual inspection of the original 30-second MP4 is still needed; CI is not physical Android acceptance.
- CI must be green on final commit SHA, main is not modified and draft PR remains unmerged.
