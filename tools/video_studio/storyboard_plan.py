"""Deterministic, offline 30/60s storyboard plan for existing LYRA mini-house requests.

This plans shots; it does NOT generate video, call an AI, authenticate, deploy,
trigger GitHub Actions, or modify the approved six-second Blender renderer.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from render_request import load_render_request, validate_request

FPS = 12
SHOT_SECONDS = 6
FRAMES_PER_SHOT = FPS * SHOT_SECONDS

SHOTS_30 = (
    ("lot", "lot_preparation", "overhead", "Preparing the building site"),
    ("foundation", "foundation", "dolly_in", "Building a strong foundation"),
    ("structure", "structure", "orbit_left", "Raising the structure"),
    ("details", "facade", "side_track", "Adding walls, roof and windows"),
    ("reveal", "finished_house", "orbit_right", "The finished home"),
)
SHOTS_60 = (
    ("lot", "lot_preparation", "overhead", "Preparing the building site"),
    ("layout", "floor_plan", "dolly_in", "Laying out the floor plan"),
    ("foundation", "foundation", "front_wide", "Building the foundation"),
    ("columns", "structure", "orbit_left", "Raising support columns"),
    ("walls", "ground_floor", "front_wide", "Constructing the ground floor"),
    ("upper", "upper_structure", "orbit_right", "Raising the upper floor"),
    ("roof", "roof", "overhead", "Finishing the roof"),
    ("facade", "facade", "side_track", "Adding windows and color"),
    ("outdoors", "outdoor_finish", "dolly_out", "Finishing outdoor details"),
    ("reveal", "finished_house", "orbit_right", "The finished home"),
)


def make_storyboard(request: dict) -> dict:
    """Use existing strict Slice 8 request validator as the one input truth."""
    spec, meta = validate_request(request)
    target = meta["target_duration_seconds"]
    shots = SHOTS_30 if target == 30 else SHOTS_60
    scenes = []
    for i, (scene_id, stage, camera, caption) in enumerate(shots):
        if stage == "upper_structure" and spec.floors == 1:
            stage, caption = "entry_details", "Finishing the entrance"
        elif stage == "outdoor_finish":
            stage = "garden" if spec.garden else "pathway"
            caption = "Creating the garden" if spec.garden else "Finishing the pathway"
        elif stage == "roof":
            caption = f"Finishing the {spec.roof_style} roof"
        elif stage == "facade":
            caption = f"Adding {spec.wall_color} walls and windows"
        elif stage == "finished_house":
            caption = f"The finished {spec.floors}-floor home"
        start_frame = i * FRAMES_PER_SHOT + 1
        scenes.append({
            "scene_id": scene_id,
            "stage": stage,
            "camera": camera,
            "caption": caption,
            "start_seconds": i * SHOT_SECONDS,
            "duration_seconds": SHOT_SECONDS,
            "start_frame": start_frame,
            "end_frame": start_frame + FRAMES_PER_SHOT - 1,
        })
    return {
        "version": 1,
        "mode": "offline_storyboard_plan_only",
        "renderer": request["renderer"],
        "project_id": meta["project_id"],
        "target_duration_seconds": target,
        "existing_preview_duration_seconds": meta["preview_duration_seconds"],
        "width": request["width"],
        "height": request["height"],
        "fps": FPS,
        "planned_frames": target * FPS,
        "house_spec": spec.manifest(),
        "scenes": scenes,
        "video_rendered": False,
        "staging_deployed": False,
        "workflow_dispatched": False,
        "owner_approval_required_before_dispatch": True,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Create an OFFLINE mini-house storyboard plan")
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    # Enforce the existing bounded 4 KB request and JSON schema.
    load_render_request(args.request)
    request = json.loads(args.request.read_text(encoding="utf-8"))
    plan = make_storyboard(request)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(plan, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("OFFLINE_PLAN_ONLY", plan["project_id"], plan["target_duration_seconds"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
