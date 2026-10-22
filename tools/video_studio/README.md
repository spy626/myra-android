# LYRA Video Studio — Slice 3 (real 3D rendering experiment)

Standalone six-second original 3D mini-house build, using Blender's free
Workbench renderer and FFmpeg. No video API key, subscription, stock video,
networked models or Android runtime modifications.

The Blender script builds a garden lot, foundation, columns, two storeys,
windows, terrace, entrance and landscaping. Objects grow through keyed motion
as a moving camera watches. It exports 72 rendered 360 x 640 portrait frames,
an editable Blender scene and a manifest. CI encodes an H264 six-second MP4,
checks real frames change and validates metadata, then uploads a temporary
GitHub Actions artifact (seven-day retention).

Local reproduction (with Blender and FFmpeg installed):

    blender -b --factory-startup --python tools/video_studio/mini_house_blender.py -- --output-dir build/video-studio
    ffmpeg -y -framerate 12 -start_number 1 -i build/video-studio/frames/frame_%04d.png -frames:v 72 -c:v libx264 -pix_fmt yuv420p -crf 23 build/video-studio/mini_house_6s.mp4
    python3 tools/video_studio/verify_mini_house.py build/video-studio

IMPORTANT HONEST LIMITATIONS

- Hardcoded demo: user prompts are NOT interpreted; this is not arbitrary
  3D house generation or a working text-to-video feature.
- Six-second low-resolution render only, NOT a finished 30/60-second Short.
- Simplified 3D assembly, not lifelike workers, physical cement or photorealism.
- Rendering occurs in desktop Blender on GitHub Actions, not on Android.
- Green automated tests confirm technical output, not high visual quality.
- Visually inspect the MP4 before expanding the pipeline.

No changes to LYRA memory, main chat, voice, Workspace or main branch.
