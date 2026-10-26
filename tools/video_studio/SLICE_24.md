# LYRA Video Studio Slice 24 — Third original construction shot + 18s verified sequence

## What Slice 24 produces (when exact commit CI is GREEN)

Three truly independent Blender scenes, each with 72 original 360x640 frames at 12fps:
1. Animated foundation slab/rebar (Slice 21)
2. Animated columns, beams and partial walls (Slice 22)
3. NEW incremental upper walls, roof and front facade (Slice 24), starting from a matching finished structure end state.

FFmpeg losslessly concatenates the first two into the previously proven 12-second intermediate; then joins a genuinely different six-second facade shot. The 18-second output contains 216 decoded frames. The assembler independently compares every decoded output frame to source footage to reject looping, duplication, dropped frames or reversed order. A six-frame contact sheet captures boundaries.

The approved source storyboard segment is **30-second target frames 73 through 288** (foundation, structure, details). This is NOT a completed 30-second video: the lot and final reveal shots are still missing, and 60-second/two-storey facade footage has NOT been implemented. Those cases are explicitly rejected by the third-shot contract rather than faked.

## Files

- tools/video_studio/facade_shot_contract.py
- tools/video_studio/render_facade_shot_blender.py
- tools/video_studio/verify_facade_shot.py
- tools/video_studio/assemble_three_shot_sequence.py
- tools/video_studio/test_facade_shot_contract.py (13 new security/continuity tests)
- .github/workflows/video-studio-slice-24.yml

## Exact CI requirements

1. New tests and all inherited Python/Node offline video-security and OAuth owner regressions GREEN.
2. Blender creates real new scene with base columns/beams/partial wall geometry matching Slice22, plus animated facades and requested flat/gable roof; no arbitrary commands or external assets.
3. Full 18-second MP4 produced, verified exact 216 frames, 12fps, 360x640 H264, and decoded hashes equal the independently rendered sources.
4. Publish MP4, Blender .blend of third shot, SHA-only manifest and visual contact sheet as temporary GitHub Actions artifacts.
5. Standard Android APK CI separately confirms unit tests, debug APK, v1/v2/v3 signing and uploaded artifact at **the exact same final SHA**. Phone pass requires actual device testing, NOT CI.

## Visual honesty and next gate

The existing 12-second sequence is stylized, and a scene cut from foundation to building-frame has a visible state/camera jump. The third scene is designed to begin with the prior completed structural geometry; this reduces, but does not scientifically prove elimination of, visual discontinuities. Manually inspect the actual 18-second MP4 and contact sheet. Technical GREEN does not mean photorealistic or cinematic.

After green and visual review, render two missing independent 30s storyboard scenes (initial empty-lot stage and finished-house reveal); then validate a complete original five-shot 30-second MP4. Do not loop the existing six-second preview to inflate duration.

## Strict safety

Do NOT merge or edit main, production Worker, disabled Cloudflare staging, LYRA memory/chat/voice or predecessor working Blender rendering. No Cloudflare deployment, OAuth activation, secrets provisioning, workflow_dispatch, authenticated rendering, paid GPU or internet API calls. CI runs only free, offline Blender/FFmpeg in a disposable read-only GitHub Actions job.
