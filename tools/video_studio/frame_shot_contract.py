"""Strict Slice 22 single-shot bridge from Slice 20 storyboard to Blender."""
from __future__ import annotations

import hashlib
import json

from storyboard_plan import make_storyboard

FPS = 12
FRAME_COUNT = 72
SHOT_SECONDS = 6
SOURCE_SCENES = {
    30: ("structure", "structure", "orbit_left"),
    60: ("columns", "structure", "orbit_left"),
}


def approved_frame_shot(request: dict) -> dict:
    """The existing Slice 8/20 validator is the only input authorization schema."""
    plan = make_storyboard(request)
    target = plan["target_duration_seconds"]
    scene_id, stage, camera = SOURCE_SCENES[target]
    matches = [s for s in plan["scenes"] if s["scene_id"] == scene_id]
    if len(matches) != 1:
        raise ValueError("Selected frame shot missing")
    scene = matches[0]
    if (scene["stage"], scene["camera"], scene["duration_seconds"]) != (
        stage, camera, SHOT_SECONDS
    ):
        raise ValueError("Selected frame shot does not match storyboard contract")
    if scene["end_frame"] - scene["start_frame"] + 1 != FRAME_COUNT:
        raise ValueError("Incorrect storyboard frame boundaries")
    canonical = json.dumps(plan, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return {
        "shot_contract_version": 1,
        "kind": "real_blender_structural_frame_shot_proof",
        "project_id": plan["project_id"],
        "target_duration_seconds": target,
        "scene_id": scene_id,
        "stage": stage,
        "camera": camera,
        "storyboard_start_seconds": scene["start_seconds"],
        "storyboard_start_frame": scene["start_frame"],
        "storyboard_end_frame": scene["end_frame"],
        "storyboard_sha256": hashlib.sha256(canonical.encode("ascii")).hexdigest(),
        "spec": plan["house_spec"],
        "fps": FPS,
        "width": 360,
        "height": 640,
        "duration_seconds": SHOT_SECONDS,
        "expected_frames": FRAME_COUNT,
        "engine": "BLENDER_WORKBENCH",
        "video_rendered_in_contract": False,
        "staging_deployed": False,
        "workflow_dispatched": False,
    }


def verify_frame_manifest(request: dict, manifest: dict) -> None:
    expected = approved_frame_shot(request)
    if not isinstance(manifest, dict) or set(manifest) != set(expected) | {
        "actual_shot_rendered", "object_count", "geometry"
    }:
        raise ValueError("Unexpected structural frame manifest keys")
    for key, value in expected.items():
        if type(manifest[key]) is not type(value) or manifest[key] != value:
            raise ValueError("Structural frame manifest mismatch: " + key)
    if manifest["actual_shot_rendered"] is not True:
        raise ValueError("No real Blender render evidence")
    geometry = manifest["geometry"]
    if type(geometry) is not dict or set(geometry) != {
        "ground_floor_columns", "top_beams", "partial_walls", "animated_objects",
        "new_structure_not_finished_house"
    }:
        raise ValueError("Missing construction geometry contract")
    for key, expected_count in (
        ("ground_floor_columns", 4),
        ("top_beams", 4),
        ("partial_walls", 3),
        ("animated_objects", 11),
    ):
        if type(geometry[key]) is not int or geometry[key] != expected_count:
            raise ValueError("Incorrect construction element count: " + key)
    if geometry["new_structure_not_finished_house"] is not True:
        raise ValueError("Not a distinct unfinished structure shot")
    if type(manifest["object_count"]) is not int or manifest["object_count"] < 18:
        raise ValueError("Missing real Blender scene objects")
