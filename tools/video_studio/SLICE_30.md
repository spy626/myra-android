# LYRA Video Studio Slice 30 — bounded denoised Cycles look development

Base: Slice29 exact final SHA 3cbce6ba0296c3e092e3637e0f3ff8289edfe05e.

## Actual implementation
This **offline, separately saved** Blender Cycles look-development scene
starts from Slice29's authentic path-traced reveal project. The exact same
original 3 camera animation frames (16, 48, 72), original house meshes,
PBR materials and three physical area lights are preserved. Render
samples increase from 8 to **32**, and **fixed CPU FFmpeg NLMeans** post-filter denoising is enabled.
The signed Ubuntu Blender build on GitHub Actions has **no OpenImageDenoise**;
Blender-native denoising is disabled rather than falsely claimed. Three new source-frame PNG stills (360x640) and a side-by-side
before/after contact sheet are generated. These are real CPU renders;
neither still-image manipulation nor fake frame interpolation.

A reusable, pure Python bounded rendering profile is added for later
work toward consistent lighting across the five storyboard scenes. The
profile is NOT falsely claimed to be installed on all scenes yet.

## CI acceptance
The Slice29 baseline Cycles scene remains unmodified, and both baseline
and refined Blender projects are opened independently in Blender.
Verification requires the original 8-sample scene with denoising disabled
and the new 32-sample scene with denoising enabled, both genuine CPU Cycles, identical scene lighting and PBR material
inventories, 3 real source RGB-decoded frames each, dimensions and nonblank
content, distinct hashes and actual denoised images. The verifier independently
re-runs the exact fixed NLMeans CPU filter on raw 32-sample image files and
compares decoded RGB SHA-256 with the produced denoised output. The report explicitly does NOT claim a quantified
noise-reduction score or subjective photorealism.

All inherited production-safe offline Python/Node checks, the exact
original 30s Workbench film and frame provenance, four RGB scene-cut
thresholds, camera handoffs, 38 physically corresponding geometry objects
and Slice28's 19 additional 3D detail meshes continue to be verified in CI.
Android push/PR tests and signed APK artifact remain mandatory.

## Limitations and security
This is **three 32-sample denoised Cycles preview images, not a
30-second Cycles video**. The proven 30-second H264 remains a stylized
Workbench output (360x640 12fps). Physically lit Cycles material
appearance may not match all earlier Workbench scenes; lighting needs
a future whole-timeline implementation before safely replacing the video.
No external/paid renderer, GPU, remote assets, arbitrary user scripts,
secrets, API dispatch or deployment.

Unchanged: LYRA memory/chat/voice, Android app functionality, Cloudflare,
all original renderers/assembly, and main. Draft PR stays unmerged.
Physical Android phone testing and human aesthetic acceptance are pending.
