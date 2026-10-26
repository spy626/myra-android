"""Slice 25: fail-closed first/last 30s storyboard shots, never an AI dispatch."""
from __future__ import annotations

from facade_shot_contract import approved_facade_shot
from storyboard_plan import make_storyboard

STAGES = {
    "lot": ("lot", "lot_preparation", "overhead", 0, 1, 72),
    "reveal": ("reveal", "finished_house", "orbit_right", 24, 289, 360),
}


def approved_edge_shot(request: dict, name: str) -> dict:
    if name not in STAGES:
        raise ValueError("Unknown original Blender shot")
    plan = make_storyboard(request)
    # This proof intentionally cannot claim a 2-floor, 60s, or garden scene.
    if (plan["target_duration_seconds"] != 30 or
        plan["house_spec"]["floors"] != 1 or plan["house_spec"]["garden"] is not False):
        raise ValueError("Slice25 real rendering supports 30s, one floor, no garden only")
    scene_id, stage, camera, start, first, last = STAGES[name]
    scenes = [s for s in plan["scenes"] if s["scene_id"] == scene_id]
    if len(scenes) != 1:
        raise ValueError("Missing or duplicate storyboard scene")
    s = scenes[0]
    if (s["stage"], s["camera"], s["start_seconds"], s["duration_seconds"],
        s["start_frame"], s["end_frame"]) != (stage, camera, start, 6, first, last):
        raise ValueError("Unapproved shot timeline or camera")
    facade = approved_facade_shot(request)
    if facade["storyboard_sha256"] != __import__("hashlib").sha256(
        __import__("json").dumps(plan, sort_keys=True, separators=(",", ":"),
                                  ensure_ascii=True).encode("utf-8")).hexdigest():
        raise ValueError("Not the same validated storyboard")
    if name == "lot" and s["end_frame"] + 1 != plan["scenes"][1]["start_frame"]:
        raise ValueError("Lot must directly precede foundation")
    if name == "reveal" and facade["storyboard_end_frame"] + 1 != s["start_frame"]:
        raise ValueError("Reveal must directly follow facade")
    return {
        "shot_contract_version": 1,
        "kind": "real_blender_original_" + name + "_shot",
        "project_id": plan["project_id"],
        "scene_id": scene_id, "stage": stage, "camera": camera,
        "target_duration_seconds": 30,
        "storyboard_start_seconds": start,
        "storyboard_start_frame": first, "storyboard_end_frame": last,
        "storyboard_sha256": facade["storyboard_sha256"],
        "spec": plan["house_spec"],
        "fps": 12, "width": 360, "height": 640,
        "duration_seconds": 6, "expected_frames": 72,
        "engine": "BLENDER_WORKBENCH",
        "video_rendered_in_contract": False,
        "staging_deployed": False, "workflow_dispatched": False,
    }


def verify_edge_manifest(request: dict, name: str, manifest: dict) -> None:
    expected = approved_edge_shot(request, name)
    if type(manifest) is not dict or set(manifest) != set(expected) | {
        "actual_shot_rendered", "object_count", "geometry"
    }:
        raise ValueError("Unexpected edge shot manifest fields")
    for key, value in expected.items():
        if type(manifest[key]) is not type(value) or manifest[key] != value:
            raise ValueError("Untrusted storyboard proof: " + key)
    if manifest["actual_shot_rendered"] is not True:
        raise ValueError("Render missing")
    geometry = manifest["geometry"]
    counts = ({
        "site_clearing": 1, "survey_guides": 2, "excavation_edges": 4,
        "survey_stakes": 4, "foundation_not_poured": True
    } if name == "lot" else {
        "existing_columns": 4, "existing_beams": 4, "completed_walls": 6,
        "roof_panels": 2 if expected["spec"]["roof_style"] == "gable" else 1,
        "existing_front_details": 4, "animated_finishing_details": 6,
    })
    if type(geometry) is not dict or set(geometry) != set(counts):
        raise ValueError("Incomplete Blender geometry evidence")
    for key, value in counts.items():
        if type(geometry[key]) is not type(value) or geometry[key] != value:
            raise ValueError("Wrong original geometry: " + key)
    if (type(manifest["object_count"]) is not int or
        manifest["object_count"] < (14 if name == "lot" else 29)):
        raise ValueError("Missing editable 3D objects")
