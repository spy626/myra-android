"""Slice 24: restrict the third real Blender shot to Slice 20's 30s facade stage.

No general text-to-video, no 60s claim, no cloud/dispatch or arbitrary assets.
This first facade proof deliberately supports only the one-floor model geometry;
two floors require a separately reviewed renderer with a real upper storey.
"""
from __future__ import annotations
import hashlib
import json

from storyboard_plan import make_storyboard
from construction_sequence_contract import assembly_plan

FRAMES = 72
SHOTS = 3


def approved_facade_shot(request: dict) -> dict:
    p = make_storyboard(request)
    if p["target_duration_seconds"] != 30 or p["house_spec"]["floors"] != 1:
        raise ValueError("Only 30s one-floor source storyboard is proven for facade")
    shot = [s for s in p["scenes"] if s["scene_id"] == "details"]
    if len(shot) != 1:
        raise ValueError("Facade storyboard scene missing or duplicated")
    s = shot[0]
    if (s["stage"], s["camera"], s["start_seconds"], s["duration_seconds"],
        s["start_frame"], s["end_frame"]) != ("facade", "side_track", 18, 6, 217, 288):
        raise ValueError("Wrong facade storyboard scene")
    preceding = assembly_plan(request)
    if (preceding["storyboard_sha256"] !=
        hashlib.sha256(json.dumps(p, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
                       .encode("utf-8")).hexdigest() or
        preceding["storyboard_end_frame"] + 1 != s["start_frame"] or
        preceding["storyboard_start_seconds"] + 12 != s["start_seconds"]):
        raise ValueError("Facade is not the next contiguous scene")
    return {
        "shot_contract_version": 1,
        "kind": "real_blender_single_floor_facade_shot",
        "project_id": p["project_id"],
        "scene_id": s["scene_id"],
        "stage": s["stage"],
        "camera": s["camera"],
        "target_duration_seconds": 30,
        "storyboard_start_seconds": s["start_seconds"],
        "storyboard_start_frame": s["start_frame"],
        "storyboard_end_frame": s["end_frame"],
        "storyboard_sha256": preceding["storyboard_sha256"],
        "spec": p["house_spec"],
        "fps": 12, "width": 360, "height": 640,
        "duration_seconds": 6, "expected_frames": FRAMES,
        "engine": "BLENDER_WORKBENCH",
        "video_rendered_in_contract": False,
        "staging_deployed": False,
        "workflow_dispatched": False,
    }


def verify_facade_manifest(request: dict, manifest: dict) -> None:
    expected = approved_facade_shot(request)
    if type(manifest) is not dict or set(manifest) != set(expected) | {
        "actual_shot_rendered", "object_count", "geometry"
    }:
        raise ValueError("Unexpected facade proof fields")
    for k, value in expected.items():
        if type(manifest[k]) is not type(value) or manifest[k] != value:
            raise ValueError("Facade proof differs from approved storyboard: " + k)
    if manifest["actual_shot_rendered"] is not True:
        raise ValueError("No real facade shot proof")
    g = manifest["geometry"]
    if type(g) is not dict or set(g) != {
        "existing_columns", "existing_beams", "existing_partial_walls",
        "animated_upper_walls", "animated_roof_panels", "animated_front_details",
        "unfinished_house_not_full_reveal",
    }:
        raise ValueError("Facade geometry missing")
    expected_roof = 2 if expected["spec"]["roof_style"] == "gable" else 1
    counts = {
        "existing_columns": 4, "existing_beams": 4, "existing_partial_walls": 3,
        "animated_upper_walls": 3, "animated_roof_panels": expected_roof,
        "animated_front_details": 4,
    }
    for k, v in counts.items():
        if type(g[k]) is not int or g[k] != v:
            raise ValueError("Incorrect Blender facade element count: " + k)
    if g["unfinished_house_not_full_reveal"] is not True:
        raise ValueError("Facade is not an incremental construction shot")
    if type(manifest["object_count"]) is not int or manifest["object_count"] < 23:
        raise ValueError("Insufficient 3D scene objects")
