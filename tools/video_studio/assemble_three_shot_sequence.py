"""Slice 24: assemble validated 6+6+6s real Blender scenes into ONE 18s MP4.

No repeated preview, no dispatch, no cloud. This is an intermediate portion
(frames 73..288) of a 30-second storyboard, NOT the full 30-second video.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

from assemble_construction_sequence import join as join_first_two
from assemble_construction_sequence import _probe_video, _decoded_frame_hashes, _concat_entry
from construction_sequence_contract import assembly_plan
from facade_shot_contract import approved_facade_shot
from verify_facade_shot import check as verify_facade
from render_request import load_render_request


def assemble(request_path: Path, foundation_dir: Path,
             structure_dir: Path, facade_dir: Path,
             output_dir: Path) -> dict:
    paths = [foundation_dir, structure_dir, facade_dir, output_dir]
    if len({str(x.resolve()) for x in paths}) != 4:
        raise ValueError("Every source and destination must use a separate folder")
    for folder in (foundation_dir, structure_dir, facade_dir):
        if folder.is_symlink() or not folder.is_dir():
            raise ValueError("Unsafe or missing Blender source folder")
    load_render_request(request_path)
    request = json.loads(request_path.read_text(encoding="utf-8"))
    first_two = assembly_plan(request)
    third = approved_facade_shot(request)
    if (third["storyboard_sha256"] != first_two["storyboard_sha256"] or
        third["storyboard_start_frame"] != first_two["storyboard_end_frame"] + 1):
        raise ValueError("Third stage not linked to same approved storyboard")

    verify_facade(str(request_path), str(facade_dir))
    facade_mp4 = facade_dir / "facade_shot_6s.mp4"
    if facade_mp4.is_symlink():
        raise ValueError("Symlinked facade clip rejected")
    _probe_video(facade_mp4, 72, 6)

    # Reuses Slice23's verified, losslessly concatenated two-shot sequence.
    output_dir.mkdir(parents=True, exist_ok=True)
    twelve_dir = output_dir / "two_shot_evidence"
    base_claim = join_first_two(request_path, foundation_dir, structure_dir, twelve_dir)
    if base_claim["sequence_frames"] != 144 or base_claim["storyboard_sha256"] != third["storyboard_sha256"]:
        raise ValueError("Untrusted first two-shot assembly")
    first_mp4 = twelve_dir / "construction_sequence_12s.mp4"
    _probe_video(first_mp4, 144, 12)
    expected_hashes = _decoded_frame_hashes(first_mp4) + _decoded_frame_hashes(facade_mp4)
    if len(expected_hashes) != 216:
        raise ValueError("Source clips do not decode to 216 frames")
    if expected_hashes[:72] == expected_hashes[72:144] or expected_hashes[72:144] == expected_hashes[144:]:
        raise ValueError("Adjacent clips repeat footage")

    output_mp4 = output_dir / "construction_sequence_18s.mp4"
    if output_mp4.exists() or output_mp4.is_symlink():
        raise ValueError("Refusing to overwrite prior render")
    listing = output_dir / "three_shot_concat.txt"
    if listing.exists() or listing.is_symlink():
        raise ValueError("Refusing existing concat list")
    listing.write_text(_concat_entry(first_mp4) + _concat_entry(facade_mp4), encoding="utf-8")
    try:
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-f", "concat", "-safe", "0", "-i", str(listing),
            "-map", "0:v:0", "-c:v", "copy", "-an", "-movflags", "+faststart",
            str(output_mp4)
        ], check=True, capture_output=True, text=True, timeout=90)
    finally:
        listing.unlink(missing_ok=True)
    if output_mp4.is_symlink() or output_mp4.stat().st_size < 45000:
        raise ValueError("No substantive assembled MP4")
    _probe_video(output_mp4, 216, 18)
    hashes = _decoded_frame_hashes(output_mp4)
    if hashes != expected_hashes:
        raise ValueError("18s MP4 changed, dropped, duplicated or reordered decoded frames")
    if len({hashes[0], hashes[71], hashes[72], hashes[143], hashes[144], hashes[215]}) < 5:
        raise ValueError("Static or duplicate sequence boundaries")

    combined = output_mp4.read_bytes()
    manifest = {
        "slice": 24,
        "mode": "three_original_offline_blender_shots",
        "project_id": third["project_id"],
        "storyboard_sha256": third["storyboard_sha256"],
        "target_duration_seconds": 30,
        "source_storyboard_first_frame": first_two["storyboard_start_frame"],
        "source_storyboard_last_frame": third["storyboard_end_frame"],
        "rendered_duration_seconds": 18,
        "rendered_frames": 216,
        "fps": 12, "width": 360, "height": 640,
        "source_scenes": [*first_two["shots"], {
            "source_scene_id": third["scene_id"],
            "source_stage": third["stage"],
            "offset_seconds": 12,
            "output_first_frame": 145,
            "output_last_frame": 216,
        }],
        "source_two_shot_mp4_sha256": hashlib.sha256(first_mp4.read_bytes()).hexdigest(),
        "source_facade_mp4_sha256": hashlib.sha256(facade_mp4.read_bytes()).hexdigest(),
        "sequence_mp4_sha256": hashlib.sha256(combined).hexdigest(),
        "sequence_mp4_bytes": len(combined),
        "all_decoded_frames_equal_sources": True,
        "full_target_video_rendered": False,
        "cloud_deployed": False,
        "workflow_dispatched": False,
        "quality_manually_approved": False,
    }
    (output_dir / "construction_sequence_18s_manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("SLICE24_REAL_18S_PASS 216 decoded frames matched in order")
    return manifest


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--request", type=Path, required=True)
    p.add_argument("--foundation-dir", type=Path, required=True)
    p.add_argument("--structure-dir", type=Path, required=True)
    p.add_argument("--facade-dir", type=Path, required=True)
    p.add_argument("--output-dir", type=Path, required=True)
    args = p.parse_args()
    assemble(args.request, args.foundation_dir, args.structure_dir,
             args.facade_dir, args.output_dir)


if __name__ == "__main__":
    main()
