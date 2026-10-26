"""Slice 23: deterministic contract for TWO genuinely separate 6s Blender shots.

Only the existing strictly validated Slice 8 mini-house request is accepted.
No arbitrary prompts, paths, URLs, workflow dispatch or cloud deployment.
"""
from __future__ import annotations

import hashlib
import json

from foundation_shot_contract import approved_foundation_shot, verify_shot_manifest
from frame_shot_contract import approved_frame_shot, verify_frame_manifest

FRAMES_PER_SHOT = 72
FPS = 12
DURATION = 12


def assembly_plan(request: dict) -> dict:
    foundation = approved_foundation_shot(request)
    frame = approved_frame_shot(request)
    if foundation["project_id"] != frame["project_id"] or (
        foundation["spec"] != frame["spec"] or
        foundation["storyboard_sha256"] != frame["storyboard_sha256"] or
        foundation["target_duration_seconds"] != frame["target_duration_seconds"]
    ):
        raise ValueError("Different storyboard or owner project")
    if foundation["storyboard_end_frame"] + 1 != frame["storyboard_start_frame"]:
        raise ValueError("Storyboard scenes are not adjacent")
    if foundation["storyboard_start_seconds"] + 6 != frame["storyboard_start_seconds"]:
        raise ValueError("Storyboard timeline gap or overlap")
    for shot in (foundation, frame):
        if (shot["fps"], shot["width"], shot["height"],
            shot["duration_seconds"], shot["expected_frames"]) != (FPS, 360, 640, 6, FRAMES_PER_SHOT):
            raise ValueError("Incompatible input render formats")
    return {
        "assembly_contract_version": 1,
        "mode": "two_distinct_offline_blender_shots",
        "project_id": foundation["project_id"],
        "target_duration_seconds": foundation["target_duration_seconds"],
        "storyboard_sha256": foundation["storyboard_sha256"],
        "spec": foundation["spec"],
        "storyboard_start_frame": foundation["storyboard_start_frame"],
        "storyboard_end_frame": frame["storyboard_end_frame"],
        "storyboard_start_seconds": foundation["storyboard_start_seconds"],
        "sequence_duration_seconds": DURATION,
        "sequence_frames": DURATION * FPS,
        "fps": FPS,
        "width": 360,
        "height": 640,
        "shots": [
            {
                "source_scene_id": foundation["scene_id"],
                "source_stage": foundation["stage"],
                "offset_seconds": 0,
                "output_first_frame": 1,
                "output_last_frame": FRAMES_PER_SHOT,
            },
            {
                "source_scene_id": frame["scene_id"],
                "source_stage": frame["stage"],
                "offset_seconds": 6,
                "output_first_frame": 73,
                "output_last_frame": 144,
            },
        ],
        "is_full_target_duration": False,
        "automated_video_dispatch": False,
        "cloudflare_deployed": False,
        "paid_service_used": False,
    }


def validate_source_manifests(request: dict, foundation: dict, structure: dict) -> dict:
    verify_shot_manifest(request, foundation)
    verify_frame_manifest(request, structure)
    plan = assembly_plan(request)
    if foundation["scene_id"] != plan["shots"][0]["source_scene_id"] or (
        structure["scene_id"] != plan["shots"][1]["source_scene_id"]
    ):
        raise ValueError("Source order differs from approved storyboard")
    return plan


def verified_output_manifest(plan: dict, foundation_mp4: bytes,
                             structure_mp4: bytes, joined_mp4: bytes) -> dict:
    """Hashes prove byte identities only; ffprobe/frame verification happens outside.

    A manifest cannot serve as proof of a render without accompanying actual MP4s.
    """
    if not isinstance(plan, dict) or plan["sequence_frames"] != 144:
        raise ValueError("Unapproved 12-second assembly")
    files = (foundation_mp4, structure_mp4, joined_mp4)
    if any(type(item) is not bytes or len(item) < 15_000 for item in files):
        raise ValueError("Missing/tiny MP4 bytes")
    hashes = [hashlib.sha256(item).hexdigest() for item in files]
    if len(set(hashes)) != 3:
        raise ValueError("Duplicate, reused or not-assembled MP4")
    return {
        **plan,
        "actual_render_verified": True,
        "foundation_mp4_sha256": hashes[0],
        "structure_mp4_sha256": hashes[1],
        "sequence_mp4_sha256": hashes[2],
        "foundation_mp4_bytes": len(foundation_mp4),
        "structure_mp4_bytes": len(structure_mp4),
        "sequence_mp4_bytes": len(joined_mp4),
        "video_rendered_seconds": DURATION,
        "visual_quality_manually_approved": False,
    }
