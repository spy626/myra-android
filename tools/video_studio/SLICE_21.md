# LYRA Video Studio Slice 21 — First Real Distinct Storyboard Shot

## What is NEW

A fully original 6-second 3D foundation-construction scene, not the old finished-house preview repeated. The Blender script builds a bare earth lot, marked survey axes, an animated concrete foundation, four growing column bases, four rising reinforcement rods, and a new moving overhead inspection camera. It accepts **only** the foundation stage from the strictly validated Slice 20 storyboard (30- or 60-second target) and the existing allowlisted house design specification. There is no general text-to-video input.

- New Blender renderer: tools/video_studio/render_foundation_shot_blender.py
- Shared bounded shot selection and manifest contract: tools/video_studio/foundation_shot_contract.py
- Actual output verifier: tools/video_studio/verify_foundation_shot.py
- 11 offline input/manifest security regression tests.
- A dedicated CI workflow renders all 72 PNG frames at 360x640, encodes a genuine six-second H.264 MP4, validates ffprobe duration and dimensions, checks frames 1/36/72 are different, and uploads MP4 + editable .blend + scene manifest + three-frame contact sheet. The user should open the contact sheet and MP4 to evaluate visual quality; green tests alone do not prove cinematic appearance.
- Full inherited Python and Node video security regressions and standard Android build remain acceptance gates.

## Safety and nonclaims

Only a fixed, pre-approved sample-blue-house request runs in GitHub Actions; this is not a user workflow dispatch or live job. GitHub Actions has read-only repository access. No Cloudflare deployment, paid provider, user OAuth, GitHub Actions write permission, remote video submission or production switch. main, the old 6s Blender renderer, the disabled Cloudflare staging manifest, LYRA chat, voice, memory and Android app code stay untouched.

This slice does NOT create a 30/60-second MP4. Its proof is precisely one distinct six-second foundation shot. Before assembling the first 30s MP4, build/visually inspect another genuinely different scene and ensure transitions, timing, storyboard semantics and audio remain correctly scoped.

## Offline reproduction on Blender/FFmpeg host

1. python3 -m unittest discover -s tools/video_studio -p 'test_foundation_shot_contract.py' -v
2. blender -b --factory-startup --python tools/video_studio/render_foundation_shot_blender.py -- --request tools/video_studio/requests/sample-blue-house.json --output-dir build/video-studio/slice21
3. ffmpeg -framerate 12 -start_number 1 -i build/video-studio/slice21/frames/foundation_%04d.png -frames:v 72 -c:v libx264 -pix_fmt yuv420p build/video-studio/slice21/foundation_shot_6s.mp4
4. python3 tools/video_studio/verify_foundation_shot.py tools/video_studio/requests/sample-blue-house.json build/video-studio/slice21
