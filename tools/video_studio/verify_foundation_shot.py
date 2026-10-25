"""Validate a real Slice 21 H264 Blender foundation shot, not metadata alone."""
from __future__ import annotations

import hashlib
import json
import struct
import subprocess
import sys
from pathlib import Path

from foundation_shot_contract import verify_shot_manifest
from render_request import load_render_request

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def check(request_path: str, output_dir: str) -> None:
    root = Path(output_dir)
    load_render_request(request_path)
    request = json.loads(Path(request_path).read_text(encoding="utf-8"))
    manifest = json.loads((root / "foundation_manifest.json").read_text(encoding="utf-8"))
    verify_shot_manifest(request, manifest)

    blend = root / "foundation_scene.blend"
    if blend.is_symlink() or blend.stat().st_size <= 2000:
        raise ValueError("Missing editable Blender foundation scene")
    frames_dir = root / "frames"
    frames = sorted(frames_dir.glob("foundation_*.png"))
    if len(frames) != 72:
        raise ValueError("Expected exactly 72 real PNG frames")
    image_hashes = []
    for i, path in enumerate(frames, 1):
        if path.name != f"foundation_{i:04d}.png" or path.is_symlink():
            raise ValueError("Missing or unexpected Blender frame")
        data = path.read_bytes()
        if len(data) < 300 or data[:8] != PNG_SIGNATURE:
            raise ValueError("Not a complete Blender PNG")
        width, height = struct.unpack(">II", data[16:24])
        if (width, height) != (360, 640):
            raise ValueError("Incorrect Blender PNG dimensions")
        image_hashes.append(hashlib.sha256(data).hexdigest())
    if len({image_hashes[0], image_hashes[35], image_hashes[-1]}) != 3:
        raise ValueError("Static or duplicated foundation timeline")

    movie = root / "foundation_shot_6s.mp4"
    if movie.is_symlink() or movie.stat().st_size < 15000:
        raise ValueError("Missing or tiny real H264 output")
    ffprobe = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=codec_name,width,height,avg_frame_rate,nb_frames",
         "-show_entries", "format=duration", "-of", "json", str(movie)],
        check=True, capture_output=True, text=True, timeout=30,
    )
    payload = json.loads(ffprobe.stdout)
    if len(payload.get("streams", [])) != 1:
        raise ValueError("Expected exactly one video stream")
    stream = payload["streams"][0]
    if (stream.get("codec_name"), stream.get("width"), stream.get("height")) != ("h264", 360, 640):
        raise ValueError("Wrong video codec or resolution")
    if stream.get("avg_frame_rate") != "12/1" or str(stream.get("nb_frames")) != "72":
        raise ValueError("Incorrect encoded frame rate or length")
    duration = float(payload.get("format", {}).get("duration", 0))
    if not 5.95 <= duration <= 6.05:
        raise ValueError("Incorrect duration; never claim a 30/60-second render")
    print("SLICE21_REAL_FOUNDATION_PASS: 72 distinct-in-time Blender frames, genuine 6s H264 shot")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_foundation_shot.py REQUEST.json OUTPUT_DIRECTORY")
    check(sys.argv[1], sys.argv[2])
