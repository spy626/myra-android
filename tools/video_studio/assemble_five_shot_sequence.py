"""Slice 25: verified 6+18+6 second source shots => a REAL 30s timeline.

Order is fixed from the strictly validated offline 30s storyboard. No scene
looping, fake duration, cloud dispatch, external assets or H264 re-render.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path

from assemble_construction_sequence import _decoded_frame_hashes, _probe_video, _concat_entry
from assemble_three_shot_sequence import assemble as assemble_middle
from edge_shot_contract import approved_edge_shot
from render_request import load_render_request
from storyboard_plan import make_storyboard
from verify_edge_shot import check as verify_edge


def assemble(request_path: Path, lot_dir: Path, foundation_dir: Path,
             structure_dir: Path, facade_dir: Path, reveal_dir: Path,
             output_dir: Path) -> dict:
    paths = (lot_dir, foundation_dir, structure_dir, facade_dir, reveal_dir, output_dir)
    if len({str(p.resolve()) for p in paths}) != 6:
        raise ValueError("Shot folders and output must be distinct")
    for folder in paths[:-1]:
        if folder.is_symlink() or not folder.is_dir():
            raise ValueError("Missing or symlinked original source folder")
    if output_dir.is_symlink():
        raise ValueError("Output must not be symlinked")
    load_render_request(request_path)
    request = json.loads(request_path.read_text(encoding="utf-8"))
    first = approved_edge_shot(request, "lot")
    last = approved_edge_shot(request, "reveal")
    storyboard = make_storyboard(request)
    expected_order = ["lot", "foundation", "structure", "details", "reveal"]
    if ([s["scene_id"] for s in storyboard["scenes"]] != expected_order or
        first["storyboard_sha256"] != last["storyboard_sha256"]):
        raise ValueError("Incorrect 30s source storyboard")
    verify_edge(str(request_path), str(lot_dir), "lot")
    verify_edge(str(request_path), str(reveal_dir), "reveal")
    lot_mp4 = lot_dir / "lot_shot_6s.mp4"
    reveal_mp4 = reveal_dir / "reveal_shot_6s.mp4"
    for movie in (lot_mp4, reveal_mp4):
        if movie.is_symlink():
            raise ValueError("Symlinked original movie")
        _probe_video(movie, 72, 6)

    output_dir.mkdir(parents=True, exist_ok=True)
    middle_dir = output_dir / "three_shot_evidence"
    middle = assemble_middle(request_path, foundation_dir, structure_dir, facade_dir, middle_dir)
    if (middle["rendered_frames"] != 216 or
        middle["source_storyboard_first_frame"] != 73 or
        middle["source_storyboard_last_frame"] != 288 or
        middle["storyboard_sha256"] != first["storyboard_sha256"]):
        raise ValueError("Incorrect authentic middle three scenes")
    middle_mp4 = middle_dir / "construction_sequence_18s.mp4"
    if middle_mp4.is_symlink():
        raise ValueError("Symlinked middle source")
    _probe_video(middle_mp4, 216, 18)

    sources = (lot_mp4, middle_mp4, reveal_mp4)
    chunks = [_decoded_frame_hashes(movie) for movie in sources]
    if [len(c) for c in chunks] != [72, 216, 72]:
        raise ValueError("Expected exactly 360 decoded source frames")
    if any(chunk[0] == chunk[-1] for chunk in chunks):
        raise ValueError("A source shot is static")
    # Also reject any two entire original shots being identical.
    source_five = [chunks[0], chunks[1][0:72], chunks[1][72:144],
                   chunks[1][144:216], chunks[2]]
    if any(source_five[i] == source_five[j]
           for i in range(5) for j in range(i + 1, 5)):
        raise ValueError("Copied/looped scene substituted for original shot")
    expected_hashes = [h for chunk in chunks for h in chunk]

    final = output_dir / "construction_sequence_30s.mp4"
    listing = output_dir / "five_scene_concat.txt"
    if final.exists() or final.is_symlink() or listing.exists() or listing.is_symlink():
        raise ValueError("Refusing to overwrite render or concat instructions")
    listing.write_text("".join(_concat_entry(m) for m in sources), encoding="utf-8")
    try:
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-f", "concat", "-safe", "0", "-i", str(listing),
            "-map", "0:v:0", "-c:v", "copy", "-an", "-movflags", "+faststart",
            str(final)
        ], check=True, capture_output=True, text=True, timeout=120)
    finally:
        listing.unlink(missing_ok=True)
    if final.is_symlink() or final.stat().st_size < 60000:
        raise ValueError("No substantive completed movie")
    _probe_video(final, 360, 30)
    actual = _decoded_frame_hashes(final)
    if actual != expected_hashes:
        raise ValueError("Assembled H264 pixels are duplicated, out-of-order or missing")
    boundaries = [0, 71, 72, 143, 144, 215, 216, 287, 288, 359]
    if any(actual[i] == actual[j] for i, j in ((0, 71), (72, 143), (144, 215),
                                               (216, 287), (288, 359))):
        raise ValueError("At least one storyboard shot never progressed")
    manifest = {
        "slice": 25, "mode": "five_distinct_original_offline_blender_shots",
        "project_id": first["project_id"],
        "storyboard_sha256": first["storyboard_sha256"],
        "target_duration_seconds": 30,
        "rendered_duration_seconds": 30, "rendered_frames": 360,
        "fps": 12, "width": 360, "height": 640,
        "source_storyboard_first_frame": 1, "source_storyboard_last_frame": 360,
        "source_scenes": [
            {
                "source_scene_id": s["scene_id"], "source_stage": s["stage"],
                "offset_seconds": s["start_seconds"],
                "output_first_frame": s["start_frame"],
                "output_last_frame": s["end_frame"],
            } for s in storyboard["scenes"]
        ],
        "source_lot_mp4_sha256": hashlib.sha256(lot_mp4.read_bytes()).hexdigest(),
        "source_middle_18s_mp4_sha256": hashlib.sha256(middle_mp4.read_bytes()).hexdigest(),
        "source_reveal_mp4_sha256": hashlib.sha256(reveal_mp4.read_bytes()).hexdigest(),
        "sequence_mp4_sha256": hashlib.sha256(final.read_bytes()).hexdigest(),
        "sequence_mp4_bytes": final.stat().st_size,
        "all_360_decoded_frames_equal_sources": True,
        "full_target_video_rendered": True,
        "cloud_deployed": False, "workflow_dispatched": False,
        "quality_manually_approved": False,
    }
    (output_dir / "construction_sequence_30s_manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("SLICE25_REAL_30S_PASS: 360 original decoded frames, five scenes")
    return manifest


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--request", type=Path, required=True)
    for name in ("lot", "foundation", "structure", "facade", "reveal", "output"):
        p.add_argument("--" + name + "-dir", type=Path, required=True)
    a = p.parse_args()
    assemble(a.request, a.lot_dir, a.foundation_dir, a.structure_dir,
             a.facade_dir, a.reveal_dir, a.output_dir)


if __name__ == "__main__":
    main()
