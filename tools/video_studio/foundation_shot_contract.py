"""Slice 21: strictly bound real Blender shot specification; no dispatch/network."""
from __future__ import annotations

import hashlib
import json

from storyboard_plan import make_storyboard

FPS = 12
SECONDS = 6
FRAMES = FPS * SECONDS
WIDTH, HEIGHT = 360, 640
SCENE_ID = "foundation"
STAGE = "foundation"
ENGINE = "BLENDER_WORKBENCH"


def approved_foundation_shot(request: dict) -> dict:
    """Select only the approved foundation stage from the existing storyboard.

    A user cannot inject camera commands, raw prompt, assets or scene paths.
    """
    plan = make_storyboard(request)
    shots = [s for s in plan["scenes"] if s["scene_id"] == SCENE_ID]
    if len(shots) != 1 or shots[0]["stage"] != STAGE:
        raise ValueError("Approved foundation stage not found")
    scene = shots[0]
    if scene["duration_seconds"] != SECONDS or scene["end_frame"] - scene["start_frame"] + 1 != FRAMES:
        raise ValueError("Unexpected storyboard duration")
    if scene["camera"] not in ("dolly_in", "front_wide"):
        raise ValueError("Unsupported foundation camera")
    canonical = json.dumps(plan, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return {
        "shot_contract_version": 1,
        "kind": "real_blender_single_shot_proof",
        "project_id": plan["project_id"],
        "scene_id": SCENE_ID,
        "stage": STAGE,
        "camera": scene["camera"],
        "target_duration_seconds": plan["target_duration_seconds"],
        "storyboard_start_seconds": scene["start_seconds"],
        "storyboard_start_frame": scene["start_frame"],
        "storyboard_end_frame": scene["end_frame"],
        "storyboard_sha256": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
        "spec": plan["house_spec"],
        "fps": FPS,
        "width": WIDTH,
        "height": HEIGHT,
        "duration_seconds": SECONDS,
        "expected_frames": FRAMES,
        "engine": ENGINE,
        "video_rendered_in_contract": False,
        "staging_deployed": False,
        "workflow_dispatched": False,
    }


def verify_shot_manifest(request: dict, manifest: dict) -> None:
    expected = approved_foundation_shot(request)
    if type(manifest) is not dict:
        raise ValueError("Shot manifest must be object")
    for key, value in expected.items():
        if manifest.get(key) != value or type(manifest.get(key)) is not type(value):
            raise ValueError("Shot manifest mismatch: " + key)
    if manifest.get("actual_shot_rendered") is not True:
        raise ValueError("Actual Blender shot is not acknowledged")
    geometry = manifest.get("geometry")
    if not isinstance(geometry, dict) or set(geometry) != {
        "foundation_slabs", "foundation_columns", "rebar_rods", "animated_objects"
    }:
        raise ValueError("Incorrect geometry manifest")
    if (geometry["foundation_slabs"] != 1 or geometry["foundation_columns"] != 4 or
        geometry["rebar_rods"] != 4 or
        type(geometry["animated_objects"]) is not int or geometry["animated_objects"] < 9):
        raise ValueError("Missing animated construction geometry")
    if (type(manifest.get("object_count")) is not int or
        manifest["object_count"] < 15):
        raise ValueError("Insufficient real 3D geometry")
