"""Check actual 72 non-static facade PNGs, Blender scene and six-second H264."""
from __future__ import annotations
import hashlib
import json
import struct
import subprocess
import sys
from pathlib import Path

from facade_shot_contract import verify_facade_manifest
from render_request import load_render_request

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def check(request_path: str, output: str):
    root = Path(output)
    load_render_request(request_path)
    request = json.loads(Path(request_path).read_text(encoding="utf-8"))
    manifest = json.loads((root / "facade_manifest.json").read_text(encoding="utf-8"))
    verify_facade_manifest(request, manifest)
    blend = root / "facade_scene.blend"
    if blend.is_symlink() or blend.stat().st_size < 2000:
        raise ValueError("Missing editable Blender geometry")
    frames = sorted((root / "frames").glob("facade_*.png"))
    if len(frames) != 72:
        raise ValueError("Missing facade render frames")
    hashes = []
    for i, file in enumerate(frames, 1):
        if file.name != f"facade_{i:04d}.png" or file.is_symlink():
            raise ValueError("Unexpected frame identity")
        data = file.read_bytes()
        if len(data) < 300 or data[:8] != PNG_SIGNATURE:
            raise ValueError("Invalid rendered PNG")
        if struct.unpack(">II", data[16:24]) != (360, 640):
            raise ValueError("Wrong Blender frame dimensions")
        hashes.append(hashlib.sha256(data).digest())
    if len({hashes[0], hashes[35], hashes[71]}) != 3:
        raise ValueError("Facade render did not progress")
    mp4 = root / "facade_shot_6s.mp4"
    if mp4.is_symlink() or mp4.stat().st_size < 15000:
        raise ValueError("Facade H264 video not produced")
    probe = subprocess.run([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,width,height,avg_frame_rate,nb_frames",
        "-show_entries", "format=duration", "-of", "json", str(mp4),
    ], check=True, capture_output=True, text=True, timeout=30)
    info = json.loads(probe.stdout)
    streams = info.get("streams", [])
    if len(streams) != 1:
        raise ValueError("Incorrect facade video streams")
    s = streams[0]
    if (s.get("codec_name"), s.get("width"), s.get("height"),
        s.get("avg_frame_rate"), str(s.get("nb_frames"))) != (
        "h264", 360, 640, "12/1", "72"
    ):
        raise ValueError("Facade H264 video codec, dimensions, frames incorrect")
    if not 5.95 <= float(info.get("format", {}).get("duration", 0)) <= 6.05:
        raise ValueError("Facade source is not six seconds")
    print("SLICE24_REAL_FACADE_PASS: 72 Blender frames, original 3D scene, 6s H264")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_facade_shot.py REQUEST.json FOLDER")
    check(sys.argv[1], sys.argv[2])
