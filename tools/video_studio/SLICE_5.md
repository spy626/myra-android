# LYRA Video Studio — Slice 5: Reviewed local request contract

## Provenance and design

This is a limited architectural adaptation of ideas discussed while studying ReelMimic (planning and shot review), OpenMontage (separate production/render stages), and general agent verification practices. **No third-party repository source code is copied or vendored.** OpenMontage's AGPL license and other repositories' licenses must be independently assessed before any direct code reuse.

## Android behavior

LYRA's existing Video Studio screen gets a separate **Review 6s Blender test request** button. A selected mini-house brief is validated locally for a small grammar: one/two floors, cream/blue/terracotta wall color, flat/gable roof, garden yes/no. Cartoon/explainer briefs cannot be exported as a house request.

The user reviews those exact interpreted attributes. Only after tapping **Share JSON** does Android launch the ordinary text-sharing chooser, using an explicit action. The JSON includes only the project ID, controlled house parameters and 6s render format. It contains **no original prompt**, conversation, credentials or private model data. This button does not trigger remote jobs, upload a video, or create any MP4.

30/60s are stored as **future target durations**; Blender currently renders only a **6-second 360×640 / 12 FPS** prototype.

## Blender offline input

A strict Python validator reads exactly these version-1 JSON fields, rejects unknown fields and types, and turns a validated structured spec into existing Blender scene geometry. The previous --prompt mode remains supported. A CI workflow executes unit tests, renders a real 6s H.264 MP4 from the checked-in Android-compatible sample, verifies frames/codec/duration and checks the Blender manifest against the JSON.

Manual reproduction with Blender, FFmpeg and Linux software graphics installed:

    python3 -m unittest discover -s tools/video_studio -p 'test_*.py' -v
    blender -b --factory-startup --python tools/video_studio/mini_house_blender.py -- --output-dir build/video-studio/test --request-json tools/video_studio/requests/sample-blue-house.json
    ffmpeg -y -framerate 12 -start_number 1 -i build/video-studio/test/frames/frame_%04d.png -frames:v 72 -c:v libx264 -pix_fmt yuv420p build/video-studio/test/mini_house_6s.mp4
    python3 tools/video_studio/verify_request_render.py tools/video_studio/requests/sample-blue-house.json build/video-studio/test

## Known gaps

- A shared Android JSON message is **not** automatically submitted to GitHub or Blender.
- No mobile Blender, GPU allocation, backend, private API key handling, upload/download, AI scene synthesis, 30/60s render or visual-quality claim.
- This is a safely constrained local contract and renderer demonstration, not a production video-generation service.
- GitHub Actions is rate/quota-limited, even when not using a paid service.
- Before later adding automatic rendering, establish an authenticated owner-managed job API, bounded quotas and explicit consent. Never hardcode repository tokens into APKs.
- Existing Workspace, voice, memory, website and main branch remain unchanged.
