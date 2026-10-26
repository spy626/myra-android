# LYRA Video Studio — Slice 29: physically lit CPU look-development proof

## Scope
Slices 25–28 render the original 30-second five-shot 360-frame H264 movie using Blender Workbench. This slice deliberately **does not replace that movie**: using Cycles on only its final scene would create color/lighting jumps and break previously proven cut continuity.

Instead, from the original editable reveal_scene.blend, a separate Blender project is created using **Cycles CPU**, 8 fixed samples, and three genuine area lights (key, fill, rim) with world illumination. All existing display-color materials become connected Principled BSDF shaders with fixed metal/roughness factors, preserving source RGB. Three real 360x640 physically lit PNG stills from the original animated reveal (frames 16, 48, 72) and a three-panel contact sheet are produced.

## Verification
The verifier reopens the saved Cycles .blend and independently verifies engine/device, sample budget, active real lights and their energy/color/position, linked material BSDF nodes, source colors, original 19 architectural meshes, and physical world node. It decodes all three PNGs with FFmpeg, checks dimensions, nonblank levels, distinct decoded hashes, and writes a JSON proof. Pure offline adversarial tests cover fixed frames/budget/lights/material categories.

All inherited 30s Workbench movie render and per-frame/source provenance, all four RGB seam thresholds, 38-object 3D geometry verification, camera endpoints, existing security tests, and Android CI remain unchanged.

## Explicit limitations
This is **three physically lit stills, not a 30-second physically rendered movie**. Samples are low to remain free/CPU feasible, and opaque blue windows are not accurate refractive glass. Do not claim photorealism, arbitrary text-to-video, Android submission, physical Android acceptance or a production deployment. No change to main, existing scene rendering, Android LYRA features, Cloudflare or dispatch. Draft PR stays unmerged.

A later isolated slice can use this evidence to plan a full-timeline-consistent PBR video with valid cut continuity and sustainable free rendering.
