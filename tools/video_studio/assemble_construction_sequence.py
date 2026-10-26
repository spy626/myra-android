"""Slice 23: assemble two VERIFIED real Blender shots, never loop a preview.

Run after two distinct 72-frame Blender/FFmpeg renders on the same local runner.
Input request paths are CLI-only operator-provided local files, never user
controlled JSON paths or remote URLs. No cloud/OAuth/workflow_dispatch.
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

from construction_sequence_contract import (
    assembly_plan, validate_source_manifests, verified_output_manifest,
)
from render_request import load_render_request
from verify_foundation_shot import check as verify_foundation
from verify_structure_shot import check as verify_structure


def _probe_video(path: Path, expected_frames: int, seconds: int) -> None:
    result = subprocess.run([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=codec_name,width,height,avg_frame_rate,nb_frames",
        "-show_entries", "format=duration", "-of", "json", str(path)
    ], check=True, capture_output=True, text=True, timeout=30)
    doc = json.loads(result.stdout)
    streams = doc.get("streams", [])
    if len(streams) != 1:
        raise ValueError("Expected exactly one video stream")
    stream = streams[0]
    if (stream.get("codec_name"), stream.get("width"), stream.get("height"),
        stream.get("avg_frame_rate"), str(stream.get("nb_frames"))) != (
        "h264", 360, 640, "12/1", str(expected_frames)
    ):
        raise ValueError("Video codec, dimensions, frame count or fps do not match")
    if abs(float(doc.get("format", {}).get("duration", 0)) - seconds) > 0.05:
        raise ValueError("Incorrect MP4 duration")


def _decoded_frame_hashes(path: Path) -> list[str]:
    """Decode actual H264 pixels, not file metadata or the proof JSON."""
    run = subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path),
        "-map", "0:v:0", "-f", "framemd5", "-"
    ], check=True, capture_output=True, text=True, timeout=90)
    return [line.split(",")[-1].strip() for line in run.stdout.splitlines()
            if line and not line.startswith("#")]


def _concat_entry(path: Path) -> str:
    if "'" in str(path) or "\n" in str(path) or "\r" in str(path):
        raise ValueError("Unsupported operator file path")
    return "file '" + str(path.resolve()) + "'\n"


def join(request_path: Path, foundation_dir: Path,
         structure_dir: Path, output_dir: Path) -> dict:
    if any(p.is_symlink() or not p.is_dir() for p in (foundation_dir, structure_dir)):
        raise ValueError("Missing or symlinked input directory")
    if foundation_dir.resolve() == structure_dir.resolve():
        raise ValueError("Expected two independent rendered directories")
    if output_dir.resolve() in (foundation_dir.resolve(), structure_dir.resolve()):
        raise ValueError("Never overwrite source evidence with output")
    # Existing size-bound schema; user input may not contain paths or commands.
    load_render_request(request_path)
    request = json.loads(request_path.read_text(encoding="utf-8"))
    foundation_manifest = json.loads((foundation_dir / "foundation_manifest.json").read_text())
    frame_manifest = json.loads((structure_dir / "structure_manifest.json").read_text())
    plan = validate_source_manifests(request, foundation_manifest, frame_manifest)

    verify_foundation(str(request_path), str(foundation_dir))
    verify_structure(str(request_path), str(structure_dir))
    first = foundation_dir / "foundation_shot_6s.mp4"
    second = structure_dir / "structure_shot_6s.mp4"
    for path in (first, second):
        if path.is_symlink():
            raise ValueError("Symlinked video source denied")
        _probe_video(path, 72, 6)
    first_hashes = _decoded_frame_hashes(first)
    second_hashes = _decoded_frame_hashes(second)
    if len(first_hashes) != 72 or len(second_hashes) != 72:
        raise ValueError("Video decoding failed before assembly")
    if first_hashes == second_hashes:
        raise ValueError("Identical footage cannot stand in for distinct scenes")

    output_dir.mkdir(parents=True, exist_ok=True)
    movie = output_dir / "construction_sequence_12s.mp4"
    if movie.exists() or movie.is_symlink():
        raise ValueError("Refusing to overwrite an existing assembled video")
    listing = output_dir / "concat_inputs.txt"
    if listing.exists() or listing.is_symlink():
        raise ValueError("Refusing pre-existing concat list")
    listing.write_text(_concat_entry(first) + _concat_entry(second), encoding="utf-8")
    try:
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-f", "concat", "-safe", "0", "-i", str(listing),
            "-map", "0:v:0", "-c:v", "copy", "-an", "-movflags", "+faststart",
            str(movie)
        ], check=True, capture_output=True, text=True, timeout=90)
    finally:
        listing.unlink(missing_ok=True)
    if movie.is_symlink() or movie.stat().st_size < 30000:
        raise ValueError("Joined video is absent or too small")
    _probe_video(movie, 144, 12)
    joined_hashes = _decoded_frame_hashes(movie)
    if joined_hashes != first_hashes + second_hashes:
        raise ValueError("Wrong scene order, frame duplication or dropped frames")
    if (joined_hashes[0] == joined_hashes[71] or
        joined_hashes[72] == joined_hashes[143]):
        raise ValueError("Rendered source animation appears static")
    manifest = verified_output_manifest(plan, first.read_bytes(), second.read_bytes(), movie.read_bytes())
    (output_dir / "construction_sequence_manifest.json").write_text(
        json.dumps(manifest, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print("SLICE23_REAL_12S_CONSTRUCTION_SEQUENCE_PASS 144 unique-timeline frames")
    return manifest


def main() -> int:
    p = argparse.ArgumentParser(description="Offline: join two actual Blender shots")
    p.add_argument("--request", type=Path, required=True)
    p.add_argument("--foundation-dir", type=Path, required=True)
    p.add_argument("--structure-dir", type=Path, required=True)
    p.add_argument("--output-dir", type=Path, required=True)
    opts = p.parse_args()
    join(opts.request, opts.foundation_dir, opts.structure_dir, opts.output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
