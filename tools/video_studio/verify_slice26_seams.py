"""Measure actual decoded RGB continuity across the four original 30s cuts.

Video is still checked for source-frame identity by Slice25's assembler.
This additional pixel gate rejects excessive visual jumps; it is not a
subjective cinematography score or proof of photorealistic quality.
"""
from __future__ import annotations

import argparse
import json
import math
import subprocess
from pathlib import Path

from assemble_construction_sequence import _probe_video

WIDTH, HEIGHT, FPS, FRAME_COUNT = 360, 640, 12, 360
BYTES_PER_FRAME = WIDTH * HEIGHT * 3
BOUNDARIES = (
    ("lot_to_foundation", 71, 72),
    ("foundation_to_structure", 143, 144),
    ("structure_to_facade", 215, 216),
    ("facade_to_reveal", 287, 288),
)
MAX_RGB_MAE = 12.0


def pixel_mae(left: bytes, right: bytes) -> float:
    if not left or len(left) != len(right):
        raise ValueError("Invalid RGB frame buffers")
    return sum(abs(a - b) for a, b in zip(left, right)) / len(left)


def require_seam_quality(scores: dict) -> None:
    expected_names = {b[0] for b in BOUNDARIES}
    if type(scores) is not dict or set(scores) != expected_names:
        raise ValueError("Missing or injected original storyboard scene boundary")
    for name, score in scores.items():
        if type(score) not in (float, int) or not math.isfinite(score):
            raise ValueError("Nonfinite seam difference for " + name)
        if score < 0 or score > MAX_RGB_MAE:
            raise ValueError("Excessive visual discontinuity at " + name +
                             " (RGB MAE %.3f)" % score)


def measure(movie: Path) -> dict:
    if movie.is_symlink() or not movie.is_file() or movie.stat().st_size < 60000:
        raise ValueError("Missing original thirty-second H264 footage")
    _probe_video(movie, FRAME_COUNT, 30)
    selected = sorted({i for _, a, b in BOUNDARIES for i in (a, b)})
    selection = "+".join("eq(n,%d)" % i for i in selected)
    result = subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
        "-i", str(movie), "-map", "0:v:0",
        "-vf", "select=" + selection,
        "-vsync", "0", "-pix_fmt", "rgb24", "-f", "rawvideo", "-"
    ], capture_output=True, check=True, timeout=120)
    if len(result.stdout) != BYTES_PER_FRAME * len(selected):
        raise ValueError("Unexpected number of real decoded RGB cut frames")
    frames = {n: result.stdout[i * BYTES_PER_FRAME:(i + 1) * BYTES_PER_FRAME]
              for i, n in enumerate(selected)}
    scores = {name: round(pixel_mae(frames[a], frames[b]), 6)
              for name, a, b in BOUNDARIES}
    require_seam_quality(scores)
    return {
        "slice": 26,
        "measurement": "mean_absolute_rgb24_difference",
        "cut_pixel_threshold": MAX_RGB_MAE,
        "source_video": movie.name,
        "scene_boundary_scores": scores,
        "all_four_seams_verified": True,
        "subjective_cinematic_quality_proven": False,
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--video", required=True, type=Path)
    p.add_argument("--report", required=True, type=Path)
    a = p.parse_args()
    if a.report.exists() or a.report.is_symlink():
        raise ValueError("Do not overwrite previous seam evidence")
    audit = measure(a.video)
    a.report.parent.mkdir(parents=True, exist_ok=True)
    a.report.write_text(json.dumps(audit, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("SLICE26_ALL_FOUR_BOUNDARY_SEAMS_PASS", audit["scene_boundary_scores"])


if __name__ == "__main__":
    main()
