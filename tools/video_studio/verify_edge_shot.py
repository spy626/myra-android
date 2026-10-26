"""Verify Blender editable scene, 72 real 360x640 PNGs and H264 source footage."""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path

from assemble_construction_sequence import _probe_video, _decoded_frame_hashes
from edge_shot_contract import verify_edge_manifest
from render_request import load_render_request

PNG = b"\x89PNG\r\n\x1a\n"


def check(request_path: str, output: str, name: str) -> None:
    if name not in ("lot", "reveal"):
        raise ValueError("Unknown storyboard stage")
    directory = Path(output)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Missing source directory or symlink")
    load_render_request(request_path)
    request = json.loads(Path(request_path).read_text(encoding="utf-8"))
    manifest_file = directory / (name + "_manifest.json")
    if manifest_file.is_symlink():
        raise ValueError("Manifest symlinks denied")
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    verify_edge_manifest(request, name, manifest)
    blend = directory / (name + "_scene.blend")
    if blend.is_symlink() or not blend.is_file() or blend.stat().st_size < 2000:
        raise ValueError("Editable Blender scene absent")
    frames_dir = directory / "frames"
    if frames_dir.is_symlink() or not frames_dir.is_dir():
        raise ValueError("Frames folder absent or symlinked")
    frames = sorted(frames_dir.glob(name + "_*.png"))
    if len(frames) != 72:
        raise ValueError("Missing original Blender PNGs")
    hashes = []
    for n, f in enumerate(frames, 1):
        if f.is_symlink() or f.name != name + "_%04d.png" % n:
            raise ValueError("Unexpected PNG identity")
        payload = f.read_bytes()
        if len(payload) < 300 or payload[:8] != PNG:
            raise ValueError("Invalid Blender PNG")
        if struct.unpack(">II", payload[16:24]) != (360, 640):
            raise ValueError("Wrong PNG width and height")
        hashes.append(hashlib.sha256(payload).hexdigest())
    if len({hashes[0], hashes[35], hashes[71]}) != 3:
        raise ValueError("Static or repeated Blender animation")
    movie = directory / (name + "_shot_6s.mp4")
    if movie.is_symlink() or not movie.is_file() or movie.stat().st_size < 15000:
        raise ValueError("No original H264 footage")
    _probe_video(movie, 72, 6)
    decoded = _decoded_frame_hashes(movie)
    if len(decoded) != 72 or decoded[0] == decoded[35] or decoded[35] == decoded[71]:
        raise ValueError("Encoded Blender animation missing movement")
    print("SLICE25_" + name.upper() + "_REAL_72_FRAMES_PASS")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--request", required=True)
    p.add_argument("--output-dir", required=True)
    p.add_argument("--scene", required=True, choices=("lot", "reveal"))
    a = p.parse_args()
    check(a.request, a.output_dir, a.scene)


if __name__ == "__main__":
    main()
