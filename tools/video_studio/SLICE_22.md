# LYRA Video Studio Slice 22 — Real Animated Building Frame Shot

## Evidence-first improvement

Build one new, original six-second house **structural frame** shot to follow the Slice 21 foundation shot in the 30/60s storyboard. This stage shows rising reinforced columns, four connecting structural beams, and three unfinished low starter walls, with an independent orbiting camera. It is **not** a replay, retime or extension of the existing completed-house clip or foundation clip. Not photorealistic.

- Strict contract (30s storyboard scene "structure", 60s storyboard scene "columns"): tools/video_studio/frame_shot_contract.py
- New Blender generator: tools/video_studio/render_structure_shot_blender.py
- Real evidence verifier: tools/video_studio/verify_structure_shot.py
- New 12-test positive/negative regression suite: tools/video_studio/test_frame_shot_contract.py
- Dedicated read-only CI: .github/workflows/video-studio-slice-22.yml

## Acceptance

Dedicated CI MUST prove a real Blender render and produce 72 360x640 PNG frames at 12fps, a six-second H264 MP4, a separately editable .blend, matching 30s source story stage/attributes, manifest with four columns, four beams, three partial walls (11 animated objects), and a contact sheet from first/middle/last frames. It checks non-static frames and video duration using ffprobe. Users must visually inspect the MP4 and contact sheet before claiming polished visuals.

Inherited Python/Node security suites and static staging guard must pass. Exact-SHA standard Android CI must also pass unit tests, APK signing v1/v2/v3 and artifact upload. The predecessor Slice 21 independently proved the foundation shot.

## Scope limits

NO 30/60s MP4 yet. These are two separate six-second ORIGINAL construction shots with distinct geometry and independent evidence, not a stitched output. No video rendering on Android, no AI-generated photoreal workers, no automatic GitHub dispatch, cloud deployment, GitHub OAuth activation, paid GPU/service, production routing or physical-phone PASS.

The isolated CI uses GitHub read-only repo scope and offline Blender/FFmpeg. Do not change main, production broker, disabled Cloudflare staging, original 6s demo, Slice21 foundation renderer, LYRA chat/voice/memory or prior Draft PRs. Advance to a third shot only after visual quality is assessed.

## Offline reproduction on Blender host

python3 -m unittest discover -s tools/video_studio -p 'test_frame_shot_contract.py' -v
blender -b --factory-startup --python tools/video_studio/render_structure_shot_blender.py -- --request tools/video_studio/requests/sample-blue-house.json --output-dir build/video-studio/slice22
ffmpeg -framerate 12 -start_number 1 -i build/video-studio/slice22/frames/structure_%04d.png -frames:v 72 -c:v libx264 -pix_fmt yuv420p build/video-studio/slice22/structure_shot_6s.mp4
python3 tools/video_studio/verify_structure_shot.py tools/video_studio/requests/sample-blue-house.json build/video-studio/slice22
