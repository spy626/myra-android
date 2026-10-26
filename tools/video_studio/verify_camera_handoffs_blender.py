"""Inspect REAL saved .blend camera keyframes, not self-attested manifests.

Check foundation->structure and structure->facade exact camera position,
direction and orthographic lens at the adjacent 6s timeline boundaries.
Run with blender -b --factory-startup --python thisfile -- ... only in CI.
"""
from __future__ import annotations

import argparse
import math
from pathlib import Path

import bpy


def camera_at(path: Path, frame: int) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size < 2000:
        raise ValueError("Missing saved editable Blender scene")
    bpy.ops.wm.open_mainfile(filepath=str(path.resolve()))
    scene = bpy.context.scene
    if scene.render.fps != 12 or scene.frame_end != 72:
        raise ValueError("Unexpected real Blender storyboard timing")
    scene.frame_set(frame)
    ob = scene.camera
    if ob is None or ob.data.type != "ORTHO":
        raise ValueError("Expected a real orthographic scene camera")
    return {
        "position": tuple(float(x) for x in ob.location),
        "rotation": tuple(float(x) for x in ob.rotation_euler),
        "ortho_scale": float(ob.data.ortho_scale),
    }


def assert_equal_camera(a: dict, b: dict, seam: str) -> None:
    for field in ("position", "rotation", "ortho_scale"):
        lhs, rhs = a[field], b[field]
        lhs = lhs if type(lhs) is tuple else (lhs,)
        rhs = rhs if type(rhs) is tuple else (rhs,)
        if len(lhs) != len(rhs):
            raise ValueError("Camera attribute count mismatch")
        if any(not math.isfinite(x) or not math.isfinite(y) or abs(x - y) > 0.0002
               for x, y in zip(lhs, rhs)):
            raise ValueError("Real Blender camera jump at " + seam + ": " + field)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--foundation", required=True, type=Path)
    p.add_argument("--structure", required=True, type=Path)
    p.add_argument("--facade", required=True, type=Path)
    a = p.parse_args()
    foundation_end = camera_at(a.foundation, 72)
    structure_start = camera_at(a.structure, 1)
    structure_end = camera_at(a.structure, 72)
    facade_start = camera_at(a.facade, 1)
    assert_equal_camera(foundation_end, structure_start, "foundation_to_structure")
    assert_equal_camera(structure_end, facade_start, "structure_to_facade")
    print("SLICE26_SAVED_BLENDER_CAMERA_ENDPOINTS_MATCH")


if __name__ == "__main__":
    main()
