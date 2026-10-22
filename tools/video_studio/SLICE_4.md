# LYRA Video Studio — Slice 4: Controlled 3D mini-house variants

This extends Slice 3 with a **limited offline text brief → house parameters → Blender geometry** path.

Supported selections:
- 1 or 2 floors (one floor / single floor / two floor / single storey / two storey; limited Hinglish)
- Cream, blue or terracotta walls
- Flat or gable/pitched roof
- Garden present or absent

Default: two-floor cream mini house with a flat roof and garden. The parser **does not interpret arbitrary extra instructions**. Unsupported buildings and conflicting options fail closed where explicitly detectable. It is not an AI model, 3D semantic planner or an unlimited text-to-video generator.

Two separate GitHub Actions jobs render editable .blend scenes and playable six-second, 360 × 640 portrait H.264 MP4s using Blender/FFmpeg with no paid AI API. Each job checks 72 rendered frames, codec/duration/resolution and that requested floors/roof/garden features exist as actual Blender objects.

Reproduction (with Blender and FFmpeg):
```bash
python3 -m unittest discover -s tools/video_studio -p 'test_house_brief.py' -v
blender -b --factory-startup --python tools/video_studio/mini_house_blender.py -- --output-dir build/video-studio/blue --prompt "Make a single-floor blue mini house with gable roof and no garden"
ffmpeg -y -framerate 12 -start_number 1 -i build/video-studio/blue/frames/frame_%04d.png -frames:v 72 -c:v libx264 -pix_fmt yuv420p -crf 23 build/video-studio/blue/mini_house_6s.mp4
python3 tools/video_studio/verify_mini_house.py build/video-studio/blue
python3 tools/video_studio/verify_house_variant.py build/video-studio/blue --floors 1 --color blue --roof gable --garden no
```

**Limits:** Android's Video Studio storyboard UI is **not connected** to this rendering pipeline. Only controlled phrases affect real 3D geometry. Still only 6-second low-detail test videos, not finished 30/60-second YouTube Shorts, photorealistic construction, advanced camera/lighting, hand animation or character creation. Green CI confirms technical correctness, not visual quality. Rendering uses a hosted runner subject to available Actions quota.

No modification to LYRA main, chat, voice, Workspace, personal AIRI memory or website generator.