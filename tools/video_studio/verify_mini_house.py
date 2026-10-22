"""Reject missing, static, incorrect, or fabricated output files."""
import hashlib
import json
import pathlib
import subprocess
import sys

def check(folder):
    root = pathlib.Path(folder)
    meta = json.loads((root / "scene_manifest.json").read_text(encoding="utf-8"))
    assert [meta.get(x) for x in ("engine", "width", "height", "fps", "duration_seconds", "expected_frames")] == [
        "BLENDER_WORKBENCH", 360, 640, 12, 6, 72], "bad Blender configuration"
    assert (root / "mini_house_scene.blend").stat().st_size > 2000, "missing editable scene"
    frames = sorted((root / "frames").glob("frame_*.png"))
    assert len(frames) == 72, "expected 72 Blender frames, got {}".format(len(frames))
    assert frames[0].name == "frame_0001.png" and frames[-1].name == "frame_0072.png"
    assert hashlib.sha256(frames[0].read_bytes()).digest() != hashlib.sha256(frames[-1].read_bytes()).digest(), "no visible animation"
    video = root / "mini_house_6s.mp4"
    assert video.stat().st_size >= 15000, "missing or tiny MP4"
    probe = subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
        "stream=codec_name,width,height,avg_frame_rate,nb_frames",
        "-show_entries", "format=duration", "-of", "json", str(video)
    ], text=True)
    data = json.loads(probe)
    stream = data["streams"][0]
    assert (stream["codec_name"], stream["width"], stream["height"]) == ("h264", 360, 640)
    assert stream["avg_frame_rate"] == "12/1" and stream.get("nb_frames") == "72"
    assert 5.95 <= float(data["format"]["duration"]) <= 6.05
    print("PASS: 72 nonstatic rendered frames, original Blender scene, 360x640 six-second H264 MP4")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_mini_house.py build-output-dir")
    check(sys.argv[1])
