"""Verify real, nonstatic Blender structure shot + exact H264 output."""
from __future__ import annotations

import hashlib
import json
import struct
import subprocess
import sys
from pathlib import Path

from frame_shot_contract import verify_frame_manifest
from render_request import load_render_request

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def check(request_path: str, folder: str) -> None:
    root = Path(folder)
    load_render_request(request_path)
    request = json.loads(Path(request_path).read_text(encoding="utf-8"))
    manifest = json.loads((root / "structure_manifest.json").read_text(encoding="utf-8"))
    verify_frame_manifest(request, manifest)

    blend = root / "structure_scene.blend"
    if blend.is_symlink() or blend.stat().st_size <= 2000:
        raise ValueError("Missing or tiny editable Blender structure scene")

    frames = sorted((root / "frames").glob("structure_*.png"))
    if len(frames) != 72:
        raise ValueError("Not 72 distinct-time Blender PNG frames")
    hashes = []
    for i, frame in enumerate(frames, 1):
        if frame.name != "structure_%04d.png" % i or frame.is_symlink():
            raise ValueError("Incorrect frame identity or unsafe symlink")
        data = frame.read_bytes()
        if len(data) < 300 or not data.startswith(PNG_SIGNATURE):
            raise ValueError("Invalid PNG frame")
        width, height = struct.unpack(">II", data[16:24])
        if (width, height) != (360, 640):
            raise ValueError("Incorrect frame dimensions")
        hashes.append(hashlib.sha256(data).digest())
    if len({hashes[0], hashes[35], hashes[71]}) != 3:
        raise ValueError("Actual structure shot is static or duplicated")

    mp4 = root / "structure_shot_6s.mp4"
    if mp4.is_symlink() or mp4.stat().st_size < 15000:
        raise ValueError("Missing real structure shot MP4")
    proc = subprocess.run([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,width,height,avg_frame_rate,nb_frames",
        "-show_entries", "format=duration", "-of", "json", str(mp4),
    ], capture_output=True, check=True, text=True, timeout=30)
    data = json.loads(proc.stdout)
    streams = data.get("streams", [])
    if len(streams) != 1:
        raise ValueError("Expected exactly one H264 stream")
    stream = streams[0]
    if (stream.get("codec_name"), stream.get("width"), stream.get("height")) != (
        "h264", 360, 640
    ):
        raise ValueError("Wrong H264 codec/resolution")
    if stream.get("avg_frame_rate") != "12/1" or str(stream.get("nb_frames")) != "72":
        raise ValueError("Wrong frame rate/count")
    if not 5.95 <= float(data.get("format", {}).get("duration", 0)) <= 6.05:
        raise ValueError("Not an exact six-second shot")
    print("SLICE22_REAL_STRUCTURE_PASS: actual animated 3D structure, 72 frames, 6s H264")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_structure_shot.py REQUEST.json OUTPUT_DIRECTORY")
    check(sys.argv[1], sys.argv[2])
