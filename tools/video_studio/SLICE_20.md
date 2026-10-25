# LYRA Video Studio Slice 20 — Offline Shot Timeline Foundation

## Scope

Create the first reviewable 30-second / 60-second storyboard using the existing Slice 8 bounded mini-house request contract. This is an offline deterministic shot plan. It does NOT generate a 30/60-second MP4.

## Useful ideas collected (no third-party code copied)

- Blender: procedural 3D animation and camera shots. Retain LYRA's existing six-second renderer.
- Remotion: learn from deterministic composition and timeline concepts; do not import its license-dependent dependency.
- FFmpeg and MoviePy: later editing/mixing only after genuinely distinct individual Blender shots exist.

## Implemented

- storyboard_plan.py reuses render_request.validate_request. It does not invent a new input parser or accept scripts, URL or arbitrary prompt content.
- 30 seconds maps to five consecutive 6-second shot descriptions; 60 seconds maps to ten. Exact 12fps scene frame boundaries and camera/stage/captions are included.
- One-floor house avoids upper-floor shots; no-garden requests become pathway details.
- test_storyboard_plan.py adds 13 tests for timeline boundaries, determinism, strict schema and no deployment/dispatch claims.
- CLI:
  python3 -m unittest discover -s tools/video_studio -p 'test_storyboard_plan.py' -v
  python3 tools/video_studio/storyboard_plan.py --request tools/video_studio/requests/sample-blue-house.json --output /tmp/lyra-storyboard.json

## Next actual rendering milestone

Render and visually inspect ONE NEW distinct storyboard shot with real Blender. Only then render all five or ten shots, join with FFmpeg, and verify frame differences, duration and MP4 integrity. Later consider audio, an editable timeline and an Android Video Studio screen.

## Security and honesty boundary

The storyboard neither renders video nor authenticates, deploys, dispatches GitHub Actions or activates Cloudflare. Owner-approved HTTPS staging and callback, OAuth secrets, actual Cloudflare free-tier quota review, hosted behavior tests and physical Android acceptance remain separate work. No changes to production Worker, existing six-second renderer, OAuth staging flags, LYRA chat/voice/memory, or main. Slice19 PR remains unmerged.
